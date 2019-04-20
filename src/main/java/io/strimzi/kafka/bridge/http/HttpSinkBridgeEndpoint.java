/*
 * Copyright 2018 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.strimzi.kafka.bridge.http;

import io.strimzi.kafka.bridge.Endpoint;
import io.strimzi.kafka.bridge.SinkBridgeEndpoint;
import io.strimzi.kafka.bridge.converter.MessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpJsonMessageConverter;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class HttpSinkBridgeEndpoint<V, K> extends SinkBridgeEndpoint<V, K> {

    private RoutingContext routingContext;

    //unique id assigned to every consumer during its creation.
    private String consumerInstanceId;

    private String consumerBaseUri;

    private MessageConverter messageConverter;

    private HttpBridgeContext httpBridgeContext;

    HttpSinkBridgeEndpoint(Vertx vertx, HttpBridgeConfig httpBridgeConfigProperties, HttpBridgeContext context) {
        super(vertx, httpBridgeConfigProperties);
        this.httpBridgeContext = context;
    }

    @Override
    public void open() {

    }

    @Override
    public void handle(Endpoint<?> endpoint) {

        routingContext = (RoutingContext) endpoint.get();
        JsonObject bodyAsJson = null;
        // TODO: it seems that getBodyAsJson raises an exception when the body is empty and not null
        try {
            bodyAsJson = routingContext.getBodyAsJson();
        } catch (Exception ex) {

        }

        messageConverter = new HttpJsonMessageConverter();

        switch (this.httpBridgeContext.getOpenApiOperation()){

            case SUBSCRIBE:

                this.topic = bodyAsJson.getString("topic");
                this.partition = bodyAsJson.getInteger("partition");
                if (bodyAsJson.containsKey("offset")) {
                    this.offset = bodyAsJson.getLong("offset");
                }
                this.kafkaTopic = this.topic;

                this.setSubscribeHandler(subscribeResult -> {
                    if (subscribeResult.succeeded()) {
                        sendConsumerSubscriptionResponse(routingContext.response());
                    }
                });

                this.setAssignHandler(assignResult -> {
                    if (assignResult.succeeded()) {
                        sendConsumerSubscriptionResponse(routingContext.response());
                    }
                });

                this.subscribe(false);
                break;

            case POLL:

                if (routingContext.request().getHeader("timeout") != null) {
                    this.pollTimeOut = Long.parseLong(routingContext.request().getHeader("timeout"));
                }

                this.consume(records -> {
                    if (records.succeeded()){
                        Buffer buffer = (Buffer) messageConverter.toMessages(records.result());
                        sendConsumerRecordsResponse(routingContext.response(), buffer);

                    } else {
                        sendConsumerRecordsFailedResponse(routingContext.response());
                    }
                });

                break;

            case DELETE_CONSUMER:

                sendConsumerDeletionResponse(routingContext.response());
                break;

            case COMMIT:

                Map<TopicPartition, OffsetAndMetadata> offsetData = new HashMap<>();

                JsonObject jsonOffsetData = bodyAsJson;

                JsonArray offsetsList = jsonOffsetData.getJsonArray("offsets");

                for (int i = 0 ; i < offsetsList.size() ; i++) {

                    TopicPartition topicPartition = new TopicPartition(offsetsList.getJsonObject(i));

                    OffsetAndMetadata offsetAndMetadata = new OffsetAndMetadata(offsetsList.getJsonObject(i));

                    offsetData.put(topicPartition, offsetAndMetadata);
                }

                this.commit(offsetData, status -> {
                    if (status.succeeded()) {
                        sendConsumerCommitOffsetResponse(routingContext.response(), status.succeeded());
                    } else {
                        sendConsumerCommitOffsetResponse(routingContext.response(), status.succeeded());
                    }
                });
                break;
        }

    }

    /**
     * Add a configuration parameter with key to the provided Properties bag from the JSON object
     *
     * @param json JSON object containing configuration parameters
     * @param props Properties bag where to put the configuration parameter
     * @param key key of the configuration parameter
     */
    private void addConfigParameter(JsonObject json, Properties props, String key) {
        if (json.containsKey(key)) {
            props.put(key, json.getString(key));
        }
    }

    @Override
    public void handle(Endpoint<?> endpoint, Handler<?> handler) {
        routingContext = (RoutingContext) endpoint.get();
        JsonObject bodyAsJson = routingContext.getBodyAsJson();

        switch (this.httpBridgeContext.getOpenApiOperation()){

            case CREATE_CONSUMER:

                // get the consumer group-id
                groupId = routingContext.pathParam("groupid");

                JsonObject json = bodyAsJson;
                // if no name, a random one is assigned
                consumerInstanceId = json.getString("name",
                        "kafka-bridge-consumer-" + UUID.randomUUID().toString());

                if (this.httpBridgeContext.getHttpSinkEndpoints().containsKey(consumerInstanceId)) {
                    routingContext.response().setStatusMessage("Consumer instance with the specified name already exists.")
                            .setStatusCode(ErrorCodeEnum.CONSUMER_ALREADY_EXISTS.getValue())
                            .end();
                    return;
                }

                // construct base URI for consumer
                String requestUri = routingContext.request().absoluteURI();
                if (!routingContext.request().path().endsWith("/")){
                    requestUri += "/";
                }
                consumerBaseUri = requestUri + "instances/" + consumerInstanceId;

                // get supported consumer configuration parameters
                Properties config = new Properties();
                addConfigParameter(json, config, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
                addConfigParameter(json, config, ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
                addConfigParameter(json, config, ConsumerConfig.FETCH_MIN_BYTES_CONFIG);

                // create the consumer
                this.initConsumer(false, config);

                ((Handler<String>) handler).handle(consumerInstanceId);

                // send consumer instance id(name) and base URI as response
                sendConsumerCreationResponse(routingContext.response(), consumerInstanceId, consumerBaseUri);
                break;
        }
    }

    private void sendConsumerCreationResponse(HttpServerResponse response, String instanceId, String uri) {
        JsonObject jsonResponse = new JsonObject();
        jsonResponse.put("instance_id", instanceId);
        jsonResponse.put("base_uri", uri);

        response.putHeader("Content-length", String.valueOf(jsonResponse.toBuffer().length()))
                .write(jsonResponse.toBuffer())
                .end();
    }

    private void sendConsumerSubscriptionResponse(HttpServerResponse response) {
        JsonObject jsonResponse = new JsonObject();
        jsonResponse.put("subscription_status", "subscribed");

        response.putHeader("Content-length", String.valueOf(jsonResponse.toBuffer().length()))
                .write(jsonResponse.toBuffer())
                .end();
    }

    private void sendConsumerRecordsResponse(HttpServerResponse response, Buffer buffer) {
        response.putHeader("Content-length", String.valueOf(buffer.length()))
                .write(buffer)
                .end();
    }

    private void sendConsumerDeletionResponse(HttpServerResponse response) {
        JsonObject jsonResponse = new JsonObject();
        jsonResponse.put("instance_id", this.consumerInstanceId);
        jsonResponse.put("status", "deleted");

        response.putHeader("Content-length", String.valueOf(jsonResponse.toBuffer().length()))
                .write(jsonResponse.toBuffer())
                .end();
    }

    private void sendConsumerCommitOffsetResponse(HttpServerResponse response, boolean result) {
        if (result){
            response.setStatusCode(200);
        } else {
            response.setStatusCode(500);
        }
        response.end();
    }

    private void sendConsumerRecordsFailedResponse(HttpServerResponse response) {
        response.setStatusCode(500);
        response.end();
    }
}

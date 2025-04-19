package com.amalitechtaskmanager.handlers.task;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;

import java.util.HashMap;
import java.util.Map;

import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;

public class GetAdminTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String TABLE_NAME = "Tasks";
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();
    private final DynamoDbClient dbClient = DynamoDbFactory.getClient();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String taskId = request.getPathParameters().get("taskId");

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("taskId", AttributeValue.fromS(taskId));

        try {
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(key)
                    .build();

            GetItemResponse response = dbClient.getItem(getItemRequest);
            if (!response.hasItem()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(404)
                        .withBody("{\"error\": \"Task not found\"}");
            }

            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, AttributeValue> entry : response.item().entrySet()) {
                result.put(entry.getKey(), attributeValueToSimpleValue(entry.getValue()));
            }

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(mapper.writeValueAsString(result));

        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}

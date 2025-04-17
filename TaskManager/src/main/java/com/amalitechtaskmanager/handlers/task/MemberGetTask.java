package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.utils.AttributeValueConverter;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class MemberGetTask implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String TABLE_NAME = "Tasks";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {

            String taskId = request.getPathParameters().get("taskId");
            if (taskId == null) {
                return errorResponse(400, "Missing taskId");
            }

            GetItemResponse response = fetchTaskFromDynamo(taskId);
            if (!response.hasItem()) {
                return errorResponse(404, "Task not found");
            }

            Map<String, Object> result = convertDynamoItemToMap(response.item());
            return successResponse(result);

        } catch (Exception e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private GetItemResponse fetchTaskFromDynamo(String taskId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("taskId", AttributeValue.fromS(taskId));

        GetItemRequest request = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .build();

        return DynamoDbFactory.getClient().getItem(request);
    }

    private Map<String, Object> convertDynamoItemToMap(Map<String, AttributeValue> item) {
        return item.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> AttributeValueConverter.attributeValueToSimpleValue(entry.getValue())
                ));
    }

    private APIGatewayProxyResponseEvent successResponse(Object body) throws JsonProcessingException {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(ObjectMapperFactory.getMapper().writeValueAsString(body));
    }


    private APIGatewayProxyResponseEvent errorResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody("{\"error\": \"" + message + "\"}");
    }
}
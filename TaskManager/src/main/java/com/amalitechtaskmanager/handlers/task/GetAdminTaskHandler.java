package com.amalitechtaskmanager.handlers.task;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;

public class GetAdminTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(GetAdminTaskHandler.class);
    private static final String TABLE_NAME = System.getenv("TASKS_TABLE");
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();
    private static final DynamoDbClient dbClient = DynamoDbFactory.getClient();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String taskId = request.getPathParameters().get("taskId");

        if (taskId == null || taskId.trim().isEmpty()) {
            return createResponse(400, "Task ID is required");
        }

        try {
            Map<String, AttributeValue> key = Map.of(
                    "taskId", AttributeValue.builder().s(taskId).build()
            );

            GetItemResponse response = dbClient.getItem(GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(key)
                    .build());

            if (!response.hasItem()) {
                return createResponse(404, "Task not found");
            }

            Map<String, Object> taskMap = convertDynamoItemToMap(response.item());
            String responseBody = mapper.writeValueAsString(taskMap);

            logger.info("Successfully retrieved task {}", taskId);
            return createResponse(200, responseBody);

        } catch (Exception e) {
            logger.error("Error retrieving task {}: {}", taskId, e.getMessage());
            return createResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    private Map<String, Object> convertDynamoItemToMap(Map<String, AttributeValue> item) {
        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            String key = entry.getKey();
            AttributeValue value = entry.getValue();

            // Handle date fields specially
            if (isDateField(key) && value.s() != null) {
                try {
                    LocalDateTime dateTime = LocalDateTime.parse(value.s(), DATE_FORMATTER);
                    result.put(key, dateTime.format(DATE_FORMATTER));
                } catch (Exception e) {
                    logger.warn("Failed to parse date for field {}: {}", key, value.s());
                    result.put(key, value.s());
                }
            } else {
                // Use the existing utility for other fields
                attributeValueToSimpleValue(value)
                        .ifPresent(v -> result.put(key, v));
            }
        }

        return result;
    }

    private boolean isDateField(String fieldName) {
        return fieldName.equals("deadline") ||
                fieldName.equals("createdAt") ||
                fieldName.equals("completed_at") ||
                fieldName.equals("completedAt");
    }
}
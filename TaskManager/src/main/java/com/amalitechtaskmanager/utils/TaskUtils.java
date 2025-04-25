package com.amalitechtaskmanager.utils;

import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.model.Task;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class TaskUtils {
    private static final DynamoDbClient dbClient = DynamoDbFactory.getClient();
    private static final ObjectMapper objectMapper = ObjectMapperFactory.getMapper();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static void updateTask(Task task, String TABLE_NAME) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("taskId", AttributeValue.fromS(task.getTaskId()));
        item.put("name", AttributeValue.fromS(task.getName()));
        item.put("description", AttributeValue.fromS(task.getDescription()));
        item.put("status", AttributeValue.fromS(String.valueOf(task.getStatus())));

        // Format deadline properly
        LocalDateTime deadline = task.getDeadline();
        if (deadline != null) {
            item.put("deadline", AttributeValue.fromS(deadline.format(DATE_FORMATTER)));
        }

        item.put("userId", AttributeValue.fromS(task.getUserId()));

        // Add createdAt if present
        if (task.getCreatedAt() != null) {
            item.put("createdAt", AttributeValue.fromS(task.getCreatedAt().format(DATE_FORMATTER)));
        }

        // Add completedAt if present
        if (task.getCompletedAt() != null) {
            item.put("completedAt", AttributeValue.fromS(task.getCompletedAt().format(DATE_FORMATTER)));
        }

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dbClient.putItem(request);
    }

    public static Task getTaskById(String taskId, String TABLE_NAME) {
        Map<String, AttributeValue> key = Map.of("taskId", AttributeValue.fromS(taskId));
        GetItemResponse response = dbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .build());

        if (!response.hasItem()) return null;

        Map<String, Object> itemMap = convertItemToMap(response.item());

        // Parse dates before conversion
        if (itemMap.containsKey("deadline")) {
            String deadlineStr = (String) itemMap.get("deadline");
            try {
                LocalDateTime deadline = LocalDateTime.parse(deadlineStr, DATE_FORMATTER);
                itemMap.put("deadline", deadline);
            } catch (Exception e) {
                // Handle parsing error
                itemMap.remove("deadline");
            }
        }

        return objectMapper.convertValue(itemMap, Task.class);
    }

    private static Map<String, Object> convertItemToMap(Map<String, AttributeValue> item) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            result.put(entry.getKey(), entry.getValue().s());
        }
        return result;
    }
}
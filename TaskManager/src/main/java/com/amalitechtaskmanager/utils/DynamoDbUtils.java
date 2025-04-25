package com.amalitechtaskmanager.utils;

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DynamoDbUtils {
    private static final Logger logger = LoggerFactory.getLogger(DynamoDbUtils.class);
    public static Optional<String> getSafeString(Map<String, AttributeValue> map, String key) {
        if (map == null || !map.containsKey(key)) return Optional.empty();
        AttributeValue val = map.get(key);
        return Optional.ofNullable(val.getS()).filter(s -> !s.isEmpty());
    }

    public static Optional<Task> parseTask(Map<String, AttributeValue> image) {
        if (image == null || image.isEmpty()) return Optional.empty();

        Task task = new Task();

        getSafeString(image, "taskId").ifPresent(task::setTaskId);
        getSafeString(image, "name").ifPresent(task::setName);
        getSafeString(image, "description").ifPresent(task::setDescription);
        getSafeString(image, "user_comment").ifPresent(task::setUserComment);
        getSafeString(image, "userId").ifPresent(task::setUserId);

        getSafeString(image, "status").ifPresent(statusStr -> {
            try {
                task.setStatus(TaskStatus.valueOf(statusStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Optionally log invalid status
            }
        });

        getSafeString(image, "deadline").ifPresent(deadlineStr -> {
            try {
                task.setDeadline(LocalDateTime.parse(deadlineStr, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception ignored) {}
        });

        getSafeString(image, "completed_at").ifPresent(completedAtStr -> {
            try {
                task.setCompletedAt(LocalDateTime.parse(completedAtStr, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception ignored) {}
        });
        
        getSafeString(image, "expired_at").ifPresent(expiredAtStr -> {
            try {
                task.setExpiredAt(LocalDateTime.parse(expiredAtStr, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception ignored) {}
        });

        return Optional.of(task);
    }
    
    /**
     * Parse a task from AWS SDK v2 AttributeValue map
     */
    public static Optional<Task> parseTaskFromSdk(Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> image) {
        if (image == null || image.isEmpty()) return Optional.empty();

        Task task = new Task();

        getStringFromSdk(image, "taskId").ifPresent(task::setTaskId);
        getStringFromSdk(image, "name").ifPresent(task::setName);
        getStringFromSdk(image, "description").ifPresent(task::setDescription);
        getStringFromSdk(image, "responsibility").ifPresent(task::setResponsibility);
        getStringFromSdk(image, "user_comment").ifPresent(task::setUserComment);
        getStringFromSdk(image, "userId").ifPresent(task::setUserId);

        getStringFromSdk(image, "status").ifPresent(statusStr -> {
            try {
                task.setStatus(TaskStatus.valueOf(statusStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Optionally log invalid status
            }
        });

        getStringFromSdk(image, "deadline").ifPresent(deadlineStr -> {
            try {
                task.setDeadline(LocalDateTime.parse(deadlineStr, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception ignored) {}
        });

        getStringFromSdk(image, "completed_at").ifPresent(completedAtStr -> {
            try {
                task.setCompletedAt(LocalDateTime.parse(completedAtStr, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception ignored) {}
        });
        
        getStringFromSdk(image, "expired_at").ifPresent(expiredAtStr -> {
            try {
                task.setExpiredAt(LocalDateTime.parse(expiredAtStr, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception ignored) {}
        });

        return Optional.of(task);
    }
    
    /**
     * Get a string value from AWS SDK v2 AttributeValue map
     */
    public static Optional<String> getStringFromSdk(Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> map, String key) {
        if (map == null || !map.containsKey(key)) return Optional.empty();
        software.amazon.awssdk.services.dynamodb.model.AttributeValue val = map.get(key);
        return Optional.ofNullable(val.s()).filter(s -> !s.isEmpty());
    }

    public static Optional<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> getTask(DynamoDbClient dynamoDbClient, String tableName, String taskId) {
        try {
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> key = new HashMap<>();
            key.put("taskId", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(taskId).build());

            GetItemRequest request = GetItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(request);
            return response.hasItem() ? Optional.of(response.item()) : Optional.empty();

        } catch (Exception e) {
            logger.error("Failed to fetch taskId {}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }
}

package com.amalitechtaskmanager.handlers.task;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

import com.amalitechtaskmanager.model.Task;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Lambda function that checks for expired tasks and updates their status.
 * This function is triggered by a scheduled EventBridge rule.
 */
public class TaskExpirationHandler implements RequestHandler<ScheduledEvent, Void> {

    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String tasksTable;
    private final String taskExpirationNotificationTopicArn;
    private final String taskDeadlineTopicArn;

    public TaskExpirationHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.snsClient = SnsClient.create();
        this.tasksTable = System.getenv("TASKS_TABLE");
        this.taskExpirationNotificationTopicArn = System.getenv("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.taskDeadlineTopicArn = System.getenv("TASK_DEADLINE_NOTIFICATION_TOPIC_ARN");
    }

    // Constructor for testing
    public TaskExpirationHandler(DynamoDbClient dynamoDbClient, SnsClient snsClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.tasksTable = System.getProperty("TASKS_TABLE");
        this.taskExpirationNotificationTopicArn = System.getProperty("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.taskDeadlineTopicArn = System.getProperty("TASK_DEADLINE_NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Starting task expiration check");
        LocalDate today = LocalDate.now();

        try {
            ScanResponse scanResponse = dynamoDbClient.scan(
                    ScanRequest.builder().tableName(tasksTable).build()
            );

            for (Map<String, AttributeValue> item : scanResponse.items()) {
                String taskId = item.get("taskId").s();
                String status = item.get("status").s();
                String deadlineStr = item.get("deadline").s();

                if ("expired".equals(status) || "completed".equals(status)) continue;

                try {
                    LocalDate deadline = LocalDate.parse(deadlineStr, dateFormatter);
                    if (deadline.isBefore(today)) {
                        context.getLogger().log("Task " + taskId + " has expired. Updating status...");

                        updateTaskStatus(taskId, "expired");

                        Task task = new Task(
                                taskId,
                                item.get("name").s(),
                                item.containsKey("description") ? item.get("description").s() : "",
                                "expired",
                                deadlineStr,
                                item.get("userId").s()
                        );

                        notifyUser(task, context);
                        notifyAdmin(task, context);
                    }
                } catch (DateTimeParseException e) {
                    context.getLogger().log("Invalid deadline format for task " + taskId + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Failed during expiration check: " + e.getMessage());
        }

        return null;
    }

    private void updateTaskStatus(String taskId, String newStatus) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tasksTable)
                .key(Map.of("taskId", AttributeValue.builder().s(taskId).build()))
                .updateExpression("SET #status = :newStatus")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":newStatus", AttributeValue.builder().s(newStatus).build()))
                .build()
        );
    }

    private void notifyUser(Task task, Context context) {
        try {
            String userId = task.getUserId();
            if (userId == null || userId.isEmpty()) {
                context.getLogger().log("Skipping user notification: Task has no assigned user.");
                return;
            }

            // General notification
            String messageBody = "Your task '" + task.getName() + "' has expired. " +
                    "The deadline was " + task.getDeadline() + ". " +
                    "Please contact your administrator for further instructions.";

            snsClient.publish(PublishRequest.builder()
                    .topicArn(taskExpirationNotificationTopicArn)
                    .subject("Task Expired: " + task.getName())
                    .message(messageBody)
                    .build());

            // Detailed filtered message (FIFO topic)
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            messageAttributes.put("user_id", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(userId)
                    .build());

            snsClient.publish(PublishRequest.builder()
                    .topicArn(taskDeadlineTopicArn)
                    .subject("Task Expired")
                    .message(objectMapper.writeValueAsString(task))
                    .messageAttributes(messageAttributes)
                    .messageGroupId(userId) // for FIFO topics
                    .messageDeduplicationId(task.getTaskId())
                    .build());

            context.getLogger().log("Sent expiration notification to user with ID: " + userId);
        } catch (Exception e) {
            context.getLogger().log("Error sending user notification: " + e.getMessage());
        }
    }

    private void notifyAdmin(Task task, Context context) {
        try {
            if (taskExpirationNotificationTopicArn != null) {
                String messageBody = "Task '" + task.getName() + "' assigned to user " +
                        task.getUserId() + " has expired. Deadline was " + task.getDeadline() + ".";

                snsClient.publish(PublishRequest.builder()
                        .topicArn(taskExpirationNotificationTopicArn)
                        .subject("Admin Alert: Task Expired")
                        .message(messageBody)
                        .messageAttributes(Map.of(
                                "for_admin", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue("true")
                                        .build()
                        ))
                        .build());

                context.getLogger().log("Sent expiration notification to admin.");
            }
        } catch (Exception e) {
            context.getLogger().log("Error sending admin notification: " + e.getMessage());
        }
    }
}

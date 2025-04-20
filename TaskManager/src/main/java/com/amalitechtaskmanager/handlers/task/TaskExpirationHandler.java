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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
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
    private final String usersTable;
    private final String taskExpirationNotificationTopicArn;
    private final String taskDeadlineTopicArn;

    /**
     * Default constructor used by Lambda runtime.
     */
    public TaskExpirationHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.snsClient = SnsClient.create();
        this.tasksTable = System.getenv("TASKS_TABLE");
        this.usersTable = System.getenv("USERS_TABLE");
        this.taskExpirationNotificationTopicArn = System.getenv("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.taskDeadlineTopicArn = System.getenv("TASK_DEADLINE_NOTIFICATION_TOPIC_ARN");
    }

    /**
     * Constructor for testing with dependency injection.
     */
    public TaskExpirationHandler(DynamoDbClient dynamoDbClient, SnsClient snsClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.tasksTable = System.getProperty("TASKS_TABLE");
        this.usersTable = System.getProperty("USERS_TABLE");
        this.taskExpirationNotificationTopicArn = System.getProperty("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.taskDeadlineTopicArn = System.getProperty("TASK_DEADLINE_NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Starting task expiration check");
        LocalDate today = LocalDate.now();

        try {
            // Scan for tasks with deadlines in the past and status not "expired" or "completed"
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(tasksTable)
                    .build();

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

            for (Map<String, AttributeValue> item : scanResponse.items()) {
                String taskId = item.get("taskId").s();
                String status = item.get("status").s();
                String deadlineStr = item.get("deadline").s();
                String userId = item.get("userId").s();
                String taskName = item.get("name").s();
                String description = item.containsKey("description") ? item.get("description").s() : "";

                // Skip tasks that are already expired or completed
                if ("expired".equals(status) || "completed".equals(status)) {
                    continue;
                }

                try {
                    LocalDate deadline = LocalDate.parse(deadlineStr, dateFormatter);

                    // Check if the deadline has passed
                    if (deadline.isBefore(today)) {
                        context.getLogger().log("Task " + taskId + " has expired. Updating status.");

                        // Update task status to expired
                        updateTaskStatus(taskId, "expired");

                        // Create a Task object for notifications
                        Task task = new Task(taskId, taskName, description, "expired", deadlineStr, userId);

                        // Send notifications
                        notifyUser(task, userId, context);
                        notifyAdmin(task, context);
                    }
                } catch (DateTimeParseException e) {
                    context.getLogger().log("Error parsing deadline for task " + taskId + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error checking for expired tasks: " + e.getMessage());
        }

        return null;
    }

    /**
     * Updates the status of a task in DynamoDB.
     */
    private void updateTaskStatus(String taskId, String newStatus) {
        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tasksTable)
                .key(Map.of("taskId", AttributeValue.builder().s(taskId).build()))
                .updateExpression("SET #status = :newStatus")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":newStatus", AttributeValue.builder().s(newStatus).build()))
                .build();

        dynamoDbClient.updateItem(updateRequest);
    }

    /**
     * Notifies the user assigned to the task that it has expired.
     */
    private void notifyUser(Task task, String userId, Context context) {
        try {
            // Get user information
            GetItemResponse userResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(usersTable)
                    .key(Map.of("userId", AttributeValue.builder().s(userId).build()))
                    .build());

            if (userResponse.hasItem()) {
                Map<String, AttributeValue> user = userResponse.item();

                // Check if user wants notifications
                boolean receiveNotifications = user.containsKey("receiveNotifications") &&
                        user.get("receiveNotifications").bool();

                if (receiveNotifications && taskExpirationNotificationTopicArn != null) {
                    String email = user.get("email").s();
                    String messageBody = "Your task '" + task.getName() + "' has expired. " +
                            "The deadline was " + task.getDeadline() + ". " +
                            "Please contact your administrator for further instructions.";

                    // Send notification via SNS
                    snsClient.publish(PublishRequest.builder()
                            .topicArn(taskExpirationNotificationTopicArn)
                            .subject("Task Expired: " + task.getName())
                            .message(messageBody)
                            .build());

                    // Also publish to the task deadline topic with user_id attribute for filtering
                    Map<String, String> attributes = new HashMap<>();
                    attributes.put("user_id", userId);

                    snsClient.publish(PublishRequest.builder()
                            .topicArn(taskDeadlineTopicArn)
                            .subject("Task Expired")
                            .message(objectMapper.writeValueAsString(task))
                            .messageAttributes(Map.of(
                                    "user_id", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                            .dataType("String")
                                            .stringValue(userId)
                                            .build()
                            ))
                            .build());

                    context.getLogger().log("Sent expiration notification to user: " + email);
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error notifying user: " + e.getMessage());
        }
    }

    /**
     * Notifies the admin that a task has expired.
     */
    private void notifyAdmin(Task task, Context context) {
        try {
            if (taskExpirationNotificationTopicArn != null) {
                String messageBody = "Task '" + task.getName() + "' assigned to user " + task.getUserId() +
                        " has expired. The deadline was " + task.getDeadline() + ".";

                // Send notification via SNS
                snsClient.publish(PublishRequest.builder()
                        .topicArn(taskExpirationNotificationTopicArn)
                        .subject("Admin Alert: Task Expired")
                        .message(messageBody)
                        .messageAttributes(Map.of(
                                "for_admin", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue("true")
                                        .build()
                        ))
                        .build());

                context.getLogger().log("Sent expiration notification to admin");
            }
        } catch (Exception e) {
            context.getLogger().log("Error notifying admin: " + e.getMessage());
        }
    }
}
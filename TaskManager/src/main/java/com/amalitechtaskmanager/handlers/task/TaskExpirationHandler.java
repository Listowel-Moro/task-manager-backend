package com.amalitechtaskmanager.handlers.task;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.model.TaskStatus;
import com.amalitechtaskmanager.utils.DynamoDbUtils;
import com.amalitechtaskmanager.utils.ExpirationRuleUtils;
import com.amalitechtaskmanager.utils.SnsUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupResponse;

public class TaskExpirationHandler implements RequestHandler<ScheduledEvent, Void> {

    private ExpirationQueueHandler expirationQueueHandler;
    private DynamoDbClient dynamoDbClient;
    private SqsClient sqsClient;
    private CognitoIdentityProviderClient cognitoClient;
    private ObjectMapper objectMapper = new ObjectMapper();
    private DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private String tasksTable;
    private String expirationQueueUrl;

    /**
     * Default constructor required by AWS Lambda
     */
    public TaskExpirationHandler() {
        this.expirationQueueHandler = new ExpirationQueueHandler();
        this.dynamoDbClient = DynamoDbClient.create();
        this.sqsClient = SqsClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.tasksTable = System.getenv("TASKS_TABLE");
        this.expirationQueueUrl = System.getenv("TASK_EXPIRATION_QUEUE_URL");

        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public TaskExpirationHandler(ExpirationQueueHandler expirationQueueHandler) {
        this.expirationQueueHandler = expirationQueueHandler;
        this.dynamoDbClient = DynamoDbClient.create();
        this.sqsClient = SqsClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.tasksTable = System.getenv("TASKS_TABLE");
        this.expirationQueueUrl = System.getenv("TASK_EXPIRATION_QUEUE_URL");

        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public TaskExpirationHandler(ExpirationQueueHandler expirationQueueHandler, DynamoDbClient dynamoDbClient, SnsClient snsClient, SqsClient sqsClient, CognitoIdentityProviderClient cognitoClient) {
        this.expirationQueueHandler = expirationQueueHandler;
        this.dynamoDbClient = dynamoDbClient;
        this.sqsClient = sqsClient;
        this.cognitoClient = cognitoClient;
        this.tasksTable = System.getProperty("TASKS_TABLE");
        this.expirationQueueUrl = System.getProperty("TASK_EXPIRATION_QUEUE_URL");
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Starting task expiration check");
        LocalDateTime now = LocalDateTime.now();

        if (event.getDetail() != null && !event.getDetail().isEmpty()) {
            try {
                Map<String, Object> detail = event.getDetail();
                String taskId = (String) detail.get("taskId");

                if (taskId != null && !taskId.isEmpty()) {
                    context.getLogger().log("Processing expiration for specific task: " + taskId);
                    processSpecificTaskExpiration(taskId, context);
                    return null;
                }
            } catch (Exception e) {
                context.getLogger().log("Error processing specific task expiration: " + e.getMessage());
            }
        }

        try {
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(tasksTable)
                    .build();

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
            int expiredCount = 0;

            for (Map<String, AttributeValue> item : scanResponse.items()) {
                try {
                    Optional<Task> taskOpt = DynamoDbUtils.parseTaskFromSdk(item);

                    if (taskOpt.isPresent()) {
                        Task task = taskOpt.get();

                        if (ExpirationRuleUtils.shouldExpireTask(task)) {
                            context.getLogger().log("Task " + task.getTaskId() + " has expired. Updating status.");
                            task.markAsExpired();
                            updateTaskStatus(task.getTaskId(), TaskStatus.EXPIRED.toString());
                            queueTaskForNotification(task, context);
                            expiredCount++;
                        }
                    }
                } catch (Exception e) {
                    String taskId = item.containsKey("taskId") ? item.get("taskId").s() : "unknown";
                    context.getLogger().log("Error processing task " + taskId + ": " + e.getMessage());
                }
            }

            context.getLogger().log("Expired " + expiredCount + " tasks");
        } catch (Exception e) {
            context.getLogger().log("Error checking for expired tasks: " + e.getMessage());
        }

        return null;
    }

    private void processSpecificTaskExpiration(String taskId, Context context) {
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(tasksTable)
                    .key(Map.of("taskId", AttributeValue.builder().s(taskId).build()))
                    .build());

            if (!response.hasItem()) {
                context.getLogger().log("Task not found: " + taskId);
                return;
            }

            Optional<Task> taskOpt = DynamoDbUtils.parseTaskFromSdk(response.item());
            if (taskOpt.isEmpty()) {
                context.getLogger().log("Could not parse task: " + taskId);
                return;
            }

            Task task = taskOpt.get();

            if (ExpirationRuleUtils.shouldExpireTask(task)) {
                context.getLogger().log("Task " + taskId + " has expired. Updating status.");
                task.markAsExpired();
                updateTaskStatus(taskId, TaskStatus.EXPIRED.toString());
                expirationQueueHandler.processNotifications(task, context);
            } else {
                context.getLogger().log("Task " + taskId + " does not need to be expired.");
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing specific task expiration for " + taskId + ": " + e.getMessage());
        }
    }

    private void updateTaskStatus(String taskId, String newStatus) {
        Map<String, String> expressionNames = new HashMap<>();
        expressionNames.put("#status", "status");

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":newStatus", AttributeValue.builder().s(newStatus).build());

        String updateExpression = "SET #status = :newStatus";

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tasksTable)
                .key(Map.of("taskId", AttributeValue.builder().s(taskId).build()))
                .updateExpression(updateExpression)
                .expressionAttributeNames(expressionNames)
                .expressionAttributeValues(expressionValues)
                .build();

        dynamoDbClient.updateItem(updateRequest);
    }

    private void queueTaskForNotification(Task task, Context context) {
        try {
            if (expirationQueueUrl == null || expirationQueueUrl.isEmpty()) {
                context.getLogger().log("Expiration queue URL not configured, processing notifications directly");
                expirationQueueHandler.processNotifications(task, context);
                return;
            }

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(expirationQueueUrl)
                    .messageBody(objectMapper.writeValueAsString(task))
                    .build();

            sqsClient.sendMessage(request);
            context.getLogger().log("Queued task " + task.getTaskId() + " for notification processing");
        } catch (Exception e) {
            context.getLogger().log("Error queueing task for notification: " + e.getMessage() +
                    ". Attempting direct notification.");
            expirationQueueHandler.processNotifications(task, context);
        }
    }
}
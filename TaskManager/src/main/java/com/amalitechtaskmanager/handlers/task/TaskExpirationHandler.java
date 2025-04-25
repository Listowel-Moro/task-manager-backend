package com.amalitechtaskmanager.handlers.task;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;

/**
 * Lambda function that checks for expired tasks and updates their status.
 * This function is triggered by a scheduled EventBridge rule.
 */
public class TaskExpirationHandler implements RequestHandler<ScheduledEvent, Void> {

    private static final String ADMIN_EMAIL = "biyip95648@mongrec.com"; // Hardcoded admin email

    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final SqsClient sqsClient;
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final String tasksTable;
    private final String taskExpirationNotificationTopicArn;
    private final String expirationQueueUrl;
    private final String userPoolId;

    /**
     * Default constructor used by Lambda runtime.
     */
    public TaskExpirationHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.snsClient = SnsClient.create();
        this.sqsClient = SqsClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.tasksTable = System.getenv("TASKS_TABLE");
        this.taskExpirationNotificationTopicArn = System.getenv("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.expirationQueueUrl = System.getenv("TASK_EXPIRATION_QUEUE_URL");
        this.userPoolId = System.getenv("USER_POOL_ID");

        // Configure ObjectMapper to handle Java 8 date/time types
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Constructor for testing with dependency injection.
     */
    public TaskExpirationHandler(DynamoDbClient dynamoDbClient, SnsClient snsClient, SqsClient sqsClient, CognitoIdentityProviderClient cognitoClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;
        this.cognitoClient = cognitoClient;
        this.tasksTable = System.getProperty("TASKS_TABLE");
        this.taskExpirationNotificationTopicArn = System.getProperty("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.expirationQueueUrl = System.getProperty("TASK_EXPIRATION_QUEUE_URL");
        this.userPoolId = System.getProperty("USER_POOL_ID");

        // Configure ObjectMapper to handle Java 8 date/time types
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Starting task expiration check");
        LocalDateTime now = LocalDateTime.now();

        // Check if this is a direct invocation for a specific task
        if (event.getDetail() != null && !event.getDetail().isEmpty()) {
            try {
                // Extract task details from the event
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

        // If not a specific task, scan for all tasks that need to be expired
        try {
            // Scan for tasks with deadlines in the past and status not EXPIRED or COMPLETED
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

                        // Check if the task should be expired
                        if (ExpirationRuleUtils.shouldExpireTask(task)) {
                            context.getLogger().log("Task " + task.getTaskId() + " has expired. Updating status.");

                            // Mark the task as expired
                            task.markAsExpired();

                            // Update the task in DynamoDB
                            updateTaskStatus(task.getTaskId(), TaskStatus.EXPIRED.toString());

                            // Queue the task for notification processing
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

    /**
     * Process expiration for a specific task
     */
    private void processSpecificTaskExpiration(String taskId, Context context) {
        try {
            // Get the task from DynamoDB
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

            // Check if the task should be expired
            if (ExpirationRuleUtils.shouldExpireTask(task)) {
                context.getLogger().log("Task " + taskId + " has expired. Updating status.");

                // Mark the task as expired
                task.markAsExpired();

                // Update the task in DynamoDB
                updateTaskStatus(taskId, TaskStatus.EXPIRED.toString());

                // Process notifications directly for this task
                processNotifications(task, context);
            } else {
                context.getLogger().log("Task " + taskId + " does not need to be expired.");
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing specific task expiration for " + taskId + ": " + e.getMessage());
        }
    }

    /**
     * Updates the status of a task in DynamoDB.
     */
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

    /**
     * Queue a task for notification processing
     */
    private void queueTaskForNotification(Task task, Context context) {
        try {
            if (expirationQueueUrl == null || expirationQueueUrl.isEmpty()) {
                context.getLogger().log("Expiration queue URL not configured, processing notifications directly");
                processNotifications(task, context);
                return;
            }

            // Send the task to SQS for reliable processing
            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(expirationQueueUrl)
                    .messageBody(objectMapper.writeValueAsString(task))
                    .build();

            sqsClient.sendMessage(request);
            context.getLogger().log("Queued task " + task.getTaskId() + " for notification processing");
        } catch (Exception e) {
            context.getLogger().log("Error queueing task for notification: " + e.getMessage() +
                    ". Attempting direct notification.");
            // Fallback to direct notification if queueing fails
            processNotifications(task, context);
        }
    }

    /**
     * Process notifications for an expired task
     */
    private void processNotifications(Task task, Context context) {
        try {
            if (taskExpirationNotificationTopicArn != null) {
                // Get user email from Cognito and subscribe to the topic
                if (task.getUserId() != null && !task.getUserId().isEmpty() && userPoolId != null && !userPoolId.isEmpty()) {
                    try {
                        // Get user from Cognito
                        AdminGetUserRequest userRequest = AdminGetUserRequest.builder()
                                .userPoolId(userPoolId)
                                .username(task.getUserId())
                                .build();

                        AdminGetUserResponse userResponse = cognitoClient.adminGetUser(userRequest);

                        // Find user email
                        String userEmail = null;
                        for (AttributeType attribute : userResponse.userAttributes()) {
                            if ("email".equals(attribute.name())) {
                                userEmail = attribute.value();
                                break;
                            }
                        }

                        if (userEmail != null && !userEmail.isEmpty()) {
                            // Subscribe user email to the topic
                            context.getLogger().log("Subscribing user email " + userEmail + " to topic");
                            SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                                    .protocol("email")
                                    .endpoint(userEmail)
                                    .topicArn(taskExpirationNotificationTopicArn)
                                    .returnSubscriptionArn(true)
                                    .build();

                            snsClient.subscribe(subscribeRequest);
                        }
                    } catch (Exception e) {
                        context.getLogger().log("Error subscribing user email: " + e.getMessage());
                    }
                }

                // Subscribe hardcoded admin email to the topic
                try {
                    context.getLogger().log("Subscribing admin email " + ADMIN_EMAIL + " to topic");
                    SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                            .protocol("email")
                            .endpoint(ADMIN_EMAIL)
                            .topicArn(taskExpirationNotificationTopicArn)
                            .returnSubscriptionArn(true)
                            .build();

                    snsClient.subscribe(subscribeRequest);
                } catch (Exception e) {
                    context.getLogger().log("Error subscribing admin email: " + e.getMessage());
                }

                // Send notification to the user
                Map<String, MessageAttributeValue> userAttributes = new HashMap<>();
                userAttributes.put("user_id", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(task.getUserId())
                        .build());

                String userMessage = String.format("EXPIRED: Task '%s' (ID: %s) has expired. The deadline was %s.",
                        task.getName(), task.getTaskId(), task.getDeadline());

                PublishRequest userRequest = PublishRequest.builder()
                        .message(userMessage)
                        .subject("Task Expired: " + task.getName())
                        .topicArn(taskExpirationNotificationTopicArn)
                        .messageAttributes(userAttributes)
                        .build();

                snsClient.publish(userRequest);
                context.getLogger().log("Sent expiration notification to user: " + task.getUserId());

                // Send notification to admin
                SnsUtils.sendAdminExpirationNotification(snsClient, taskExpirationNotificationTopicArn, task, ADMIN_EMAIL);
                context.getLogger().log("Sent expiration notification to admin for task: " + task.getTaskId());
            } else {
                context.getLogger().log("Notification topic not configured");
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing notifications: " + e.getMessage());
        }
    }
}
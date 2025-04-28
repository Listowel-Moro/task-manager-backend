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
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicRequest;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicResponse;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.Subscription;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupResponse;

public class TaskExpirationHandler implements RequestHandler<ScheduledEvent, Void> {

    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final SqsClient sqsClient;
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final String tasksTable;
    private final String taskExpirationUserNotificationTopicArn;
    private final String taskExpirationAdminNotificationTopicArn;
    private final String expirationQueueUrl;
    private final String userPoolId;

    public TaskExpirationHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.snsClient = SnsClient.create();
        this.sqsClient = SqsClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.tasksTable = System.getenv("TASKS_TABLE");
        this.taskExpirationUserNotificationTopicArn = System.getenv("TASK_EXPIRATION_USER_NOTIFICATION_TOPIC_ARN");
        this.taskExpirationAdminNotificationTopicArn = System.getenv("TASK_EXPIRATION_ADMIN_NOTIFICATION_TOPIC_ARN");
        this.expirationQueueUrl = System.getenv("TASK_EXPIRATION_QUEUE_URL");
        this.userPoolId = System.getenv("USER_POOL_ID");

        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public TaskExpirationHandler(DynamoDbClient dynamoDbClient, SnsClient snsClient, SqsClient sqsClient, CognitoIdentityProviderClient cognitoClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;
        this.cognitoClient = cognitoClient;
        this.tasksTable = System.getProperty("TASKS_TABLE");
        this.taskExpirationUserNotificationTopicArn = System.getProperty("TASK_EXPIRATION_USER_NOTIFICATION_TOPIC_ARN");
        this.taskExpirationAdminNotificationTopicArn = System.getProperty("TASK_EXPIRATION_ADMIN_NOTIFICATION_TOPIC_ARN");
        this.expirationQueueUrl = System.getProperty("TASK_EXPIRATION_QUEUE_URL");
        this.userPoolId = System.getProperty("USER_POOL_ID");

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
                processNotifications(task, context);
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
                processNotifications(task, context);
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
            processNotifications(task, context);
        }
    }

    private List<String> getAdminEmails(Context context) {
        try {
            ListUsersInGroupRequest listUsersInGroupRequest = ListUsersInGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .groupName("Admins")
                    .build();

            ListUsersInGroupResponse response = cognitoClient.listUsersInGroup(listUsersInGroupRequest);
            context.getLogger().log("Found " + response.users().size() + " users in the Admins group");
            context.getLogger().log(response.toString());

            List<String> emails = response.users().stream()
                    .map(user -> user.attributes().stream()
                            .filter(attr -> attr.name().equals("email"))
                            .findFirst()
                            .map(AttributeType::value)
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            context.getLogger().log("Found " + emails.size() + " admin emails from Cognito");
            return emails;
        } catch (Exception e) {
            context.getLogger().log("Error fetching admin emails: " + e.getMessage());
            return List.of();
        }
    }

    private boolean isEmailSubscribed(String email, String topicArn, Context context) {
        try {
            ListSubscriptionsByTopicRequest request = ListSubscriptionsByTopicRequest.builder()
                    .topicArn(topicArn)
                    .build();

            ListSubscriptionsByTopicResponse response = snsClient.listSubscriptionsByTopic(request);

            for (Subscription subscription : response.subscriptions()) {
                if ("email".equals(subscription.protocol()) && email.equals(subscription.endpoint())) {
                    context.getLogger().log("Email " + email + " is already subscribed to topic " + topicArn);
                    return true;
                }
            }

            context.getLogger().log("Email " + email + " is not yet subscribed to topic " + topicArn);
            return false;
        } catch (Exception e) {
            context.getLogger().log("Error checking subscription status for " + email + " on topic " + topicArn + ": " + e.getMessage());
            return false;
        }
    }

    private void subscribeEmailIfNeeded(String email, String topicArn, Context context) {
        if (email == null || email.isEmpty()) {
            return;
        }

        try {
            if (!isEmailSubscribed(email, topicArn, context)) {
                context.getLogger().log("Subscribing email " + email + " to topic " + topicArn);
                SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                        .protocol("email")
                        .endpoint(email)
                        .topicArn(topicArn)
                        .returnSubscriptionArn(true)
                        .build();

                snsClient.subscribe(subscribeRequest);
                context.getLogger().log("Successfully subscribed " + email + " to topic " + topicArn);
            }
        } catch (Exception e) {
            context.getLogger().log("Error subscribing email " + email + " to topic " + topicArn + ": " + e.getMessage());
        }
    }

    private void processNotifications(Task task, Context context) {
        try {
            if (taskExpirationUserNotificationTopicArn != null && taskExpirationAdminNotificationTopicArn != null) {
                String userEmail = null;
                if (task.getUserId() != null && !task.getUserId().isEmpty() && userPoolId != null && !userPoolId.isEmpty()) {
                    try {
                        AdminGetUserRequest userRequest = AdminGetUserRequest.builder()
                                .userPoolId(userPoolId)
                                .username(task.getUserId())
                                .build();

                        AdminGetUserResponse userResponse = cognitoClient.adminGetUser(userRequest);

                        for (AttributeType attribute : userResponse.userAttributes()) {
                            if ("email".equals(attribute.name())) {
                                userEmail = attribute.value();
                                break;
                            }
                        }

                        if (userEmail != null && !userEmail.isEmpty()) {
                            subscribeEmailIfNeeded(userEmail, taskExpirationUserNotificationTopicArn, context);
                        }
                    } catch (Exception e) {
                        context.getLogger().log("Error fetching user email: " + e.getMessage());
                    }
                }

                List<String> adminEmails = getAdminEmails(context);
                context.getLogger().log("Found the following " + adminEmails.size() + " admin emails from Cognito");

                for (String adminEmail : adminEmails) {
                    subscribeEmailIfNeeded(adminEmail, taskExpirationAdminNotificationTopicArn, context);
                }

                if (userEmail != null && !userEmail.isEmpty()) {
                    String userSubject = "Task Expired: " + task.getName();
                    String userMessage = String.format("EXPIRED: Task '%s' (ID: %s) has expired. The deadline was %s.",
                            task.getName(), task.getTaskId(), task.getDeadline());
                    SnsUtils.sendExpirationEmailNotification(snsClient, taskExpirationUserNotificationTopicArn, userEmail, userSubject, userMessage);
                    context.getLogger().log("Sent expiration notification to user: " + task.getUserId());
                }

                if (!adminEmails.isEmpty()) {
                    for (String adminEmail : adminEmails) {
                        String adminSubject = "Admin Alert: " + task.getName();
                        String adminMessage = String.format("Admin Alert: Task '%s' (ID: %s) assigned to user %s has expired. The deadline was %s.",
                                task.getName(), task.getTaskId(), task.getUserId(), task.getDeadline());
                        SnsUtils.sendExpirationEmailNotification(snsClient, taskExpirationAdminNotificationTopicArn, adminEmail, adminSubject, adminMessage);
                    }
                    context.getLogger().log("Sent expiration notification to " + adminEmails.size() + " admins for task: " + task.getTaskId());
                } else {
                    context.getLogger().log("No admin emails found to send notifications to");
                }
            } else {
                context.getLogger().log("Notification topic not configured");
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing notifications: " + e.getMessage());
        }
    }
}
package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.utils.SnsUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicRequest;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicResponse;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.Subscription;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lambda function that processes task expiration events from SQS.
 * This provides reliable processing of expiration notifications.
 */
public class ExpirationQueueHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(ExpirationQueueHandler.class);

    private final SnsClient snsClient;
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper;

    private final String taskExpirationNotificationTopicArn;
    private final String userPoolId;

    /**
     * Default constructor used by Lambda runtime.
     */
    public ExpirationQueueHandler() {
        this.snsClient = SnsClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.taskExpirationNotificationTopicArn = System.getenv("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.userPoolId = System.getenv("USER_POOL_ID");

        // Configure ObjectMapper to handle Java 8 date/time types
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Constructor for testing with dependency injection.
     */
    public ExpirationQueueHandler(SnsClient snsClient, CognitoIdentityProviderClient cognitoClient) {
        this.snsClient = snsClient;
        this.cognitoClient = cognitoClient;
        this.taskExpirationNotificationTopicArn = System.getProperty("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
        this.userPoolId = System.getProperty("USER_POOL_ID");

        // Configure ObjectMapper to handle Java 8 date/time types
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSMessage message : event.getRecords()) {
            try {
                // Parse the task from the message
                Task task = objectMapper.readValue(message.getBody(), Task.class);

                // Process notifications for the task
                processNotifications(task, context);
            } catch (Exception e) {
                logger.error("Error processing expiration message: {}", e.getMessage(), e);
                context.getLogger().log("Error processing expiration message: " + e.getMessage());
                // Don't throw an exception to avoid poison pill messages
                // The message will be moved to the dead-letter queue after max retries
            }
        }

        return null;
    }

    /**
     * Fetch admin emails from Cognito user pool
     */
    private List<String> getAdminEmails(Context context) {
        try {
            ListUsersInGroupRequest listUsersInGroupRequest = ListUsersInGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .groupName("admin")
                    .build();

            ListUsersInGroupResponse response = cognitoClient.listUsersInGroup(listUsersInGroupRequest);

            List<String> emails = response.users().stream()
                    .map(user -> user.attributes().stream()
                            .filter(attr -> attr.name().equals("email"))
                            .findFirst()
                            .map(AttributeType::value)
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            logger.info("Found {} admin emails from Cognito", emails.size());
            return emails;
        } catch (Exception e) {
            logger.error("Error fetching admin emails: {}", e.getMessage(), e);
            return List.of(); // Return empty list on error
        }
    }

    /**
     * Check if an email is already subscribed to the SNS topic
     */
    private boolean isEmailSubscribed(String email) {
        try {
            ListSubscriptionsByTopicRequest request = ListSubscriptionsByTopicRequest.builder()
                    .topicArn(taskExpirationNotificationTopicArn)
                    .build();

            ListSubscriptionsByTopicResponse response = snsClient.listSubscriptionsByTopic(request);

            for (Subscription subscription : response.subscriptions()) {
                if ("email".equals(subscription.protocol()) && email.equals(subscription.endpoint())) {
                    logger.info("Email {} is already subscribed to topic", email);
                    return true;
                }
            }

            logger.info("Email {} is not yet subscribed to topic", email);
            return false;
        } catch (Exception e) {
            logger.error("Error checking subscription status: {}", e.getMessage(), e);
            return false; // Assume not subscribed on error
        }
    }

    /**
     * Subscribe an email to the SNS topic if not already subscribed
     */
    private void subscribeEmailIfNeeded(String email) {
        if (email == null || email.isEmpty()) {
            return;
        }

        try {
            if (!isEmailSubscribed(email)) {
                logger.info("Subscribing email {} to topic", email);
                SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                        .protocol("email")
                        .endpoint(email)
                        .topicArn(taskExpirationNotificationTopicArn)
                        .returnSubscriptionArn(true)
                        .build();

                snsClient.subscribe(subscribeRequest);
                logger.info("Successfully subscribed {} to topic", email);
            }
        } catch (Exception e) {
            logger.error("Error subscribing email {}: {}", email, e.getMessage(), e);
        }
    }

    /**
     * Process notifications for an expired task
     */
    private void processNotifications(Task task, Context context) {
        try {
            if (taskExpirationNotificationTopicArn != null) {
                // Get user email from Cognito
                String userEmail = null;
                if (task.getUserId() != null && !task.getUserId().isEmpty() && userPoolId != null && !userPoolId.isEmpty()) {
                    try {
                        // Get user from Cognito
                        AdminGetUserRequest userRequest = AdminGetUserRequest.builder()
                                .userPoolId(userPoolId)
                                .username(task.getUserId())
                                .build();

                        AdminGetUserResponse userResponse = cognitoClient.adminGetUser(userRequest);

                        // Find user email
                        for (AttributeType attribute : userResponse.userAttributes()) {
                            if ("email".equals(attribute.name())) {
                                userEmail = attribute.value();
                                break;
                            }
                        }

                        if (userEmail != null && !userEmail.isEmpty()) {
                            // Subscribe user email to the topic if not already subscribed
                            subscribeEmailIfNeeded(userEmail);
                        }
                    } catch (Exception e) {
                        logger.error("Error fetching user email: {}", e.getMessage(), e);
                    }
                }

                // Get admin emails from Cognito user pool
                List<String> adminEmails = getAdminEmails(context);

                // Subscribe admin emails to the topic if not already subscribed
                for (String adminEmail : adminEmails) {
                    subscribeEmailIfNeeded(adminEmail);
                }

                // Send notification to the user
                if (userEmail != null && !userEmail.isEmpty()) {
                    Map<String, MessageAttributeValue> userAttributes = new HashMap<>();
                    userAttributes.put("user_id", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(task.getUserId())
                            .build());

                    // Filter to ensure only the user receives this notification
                    userAttributes.put("email", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(userEmail)
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
                    logger.info("Sent expiration notification to user: {}", task.getUserId());
                }

                // Send notification to admins
                if (!adminEmails.isEmpty()) {
                    for (String adminEmail : adminEmails) {
                        SnsUtils.sendAdminExpirationNotification(snsClient, taskExpirationNotificationTopicArn, task, adminEmail);
                    }
                    logger.info("Sent expiration notification to {} admins for task: {}", adminEmails.size(), task.getTaskId());
                } else {
                    logger.warn("No admin emails found to send notifications to");
                }
            } else {
                logger.warn("Notification topic not configured");
            }
        } catch (Exception e) {
            logger.error("Error processing notifications for task {}: {}", task.getTaskId(), e.getMessage(), e);
        }
    }
}
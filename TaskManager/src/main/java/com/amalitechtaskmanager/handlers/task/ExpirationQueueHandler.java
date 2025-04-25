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
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;

import java.util.HashMap;
import java.util.Map;

/**
 * Lambda function that processes task expiration events from SQS.
 * This provides reliable processing of expiration notifications.
 */
public class ExpirationQueueHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(ExpirationQueueHandler.class);
    private static final String ADMIN_EMAIL = "biyip95648@mongrec.com"; // Hardcoded admin email

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
                logger.info("Sent expiration notification to user: {}", task.getUserId());

                // Send notification to admin
                SnsUtils.sendAdminExpirationNotification(snsClient, taskExpirationNotificationTopicArn, task, ADMIN_EMAIL);
                logger.info("Sent expiration notification to admin for task: {}", task.getTaskId());
            } else {
                logger.warn("Notification topic not configured");
            }
        } catch (Exception e) {
            logger.error("Error processing notifications for task {}: {}", task.getTaskId(), e.getMessage(), e);
        }
    }
}
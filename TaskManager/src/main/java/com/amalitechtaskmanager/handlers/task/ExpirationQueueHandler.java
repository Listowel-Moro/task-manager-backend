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
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupResponse;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ExpirationQueueHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(ExpirationQueueHandler.class);

    private final SnsClient snsClient;
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper;

    private final String taskExpirationUserNotificationTopicArn;
    private final String taskExpirationAdminNotificationTopicArn;
    private final String userPoolId;

    public ExpirationQueueHandler() {
        this.snsClient = SnsClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.taskExpirationUserNotificationTopicArn = System.getenv("TASK_EXPIRATION_USER_NOTIFICATION_TOPIC_ARN");
        this.taskExpirationAdminNotificationTopicArn = System.getenv("TASK_EXPIRATION_ADMIN_NOTIFICATION_TOPIC_ARN");
        this.userPoolId = System.getenv("USER_POOL_ID");

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ExpirationQueueHandler(SnsClient snsClient, CognitoIdentityProviderClient cognitoClient) {
        this.snsClient = snsClient;
        this.cognitoClient = cognitoClient;
        this.taskExpirationUserNotificationTopicArn = System.getProperty("TASK_EXPIRATION_USER_NOTIFICATION_TOPIC_ARN");
        this.taskExpirationAdminNotificationTopicArn = System.getProperty("TASK_EXPIRATION_ADMIN_NOTIFICATION_TOPIC_ARN");
        this.userPoolId = System.getProperty("USER_POOL_ID");

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSMessage message : event.getRecords()) {
            try {
                Task task = objectMapper.readValue(message.getBody(), Task.class);
                processNotifications(task, context);
            } catch (Exception e) {
                logger.error("Error processing expiration message: {}", e.getMessage(), e);
                context.getLogger().log("Error processing expiration message: " + e.getMessage());
            }
        }

        return null;
    }

    public List<String> getAdminEmails(Context context) {
        try {
            ListUsersInGroupRequest listUsersInGroupRequest = ListUsersInGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .groupName("Admins")
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
            context.getLogger().log("UserPoolId : " + userPoolId);
            return emails;
        } catch (Exception e) {
            logger.error("Error fetching admin emails: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public void processNotifications(Task task, Context context) {
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
                    } catch (Exception e) {
                        logger.error("Error fetching user email: {}", e.getMessage(), e);
                    }
                }

                List<String> adminEmails = getAdminEmails(context);
                context.getLogger().log("Found the following " + adminEmails.size() + " admin emails from Cognito");

                if (userEmail != null && !userEmail.isEmpty()) {
                    String userSubject = "Task Expired: " + task.getName();
                    String userMessage = String.format("EXPIRED: Task '%s' (ID: %s) has expired. The deadline was %s.",
                            task.getName(), task.getTaskId(), task.getDeadline());
                    SnsUtils.sendEmailNotification(taskExpirationUserNotificationTopicArn, userEmail, userSubject, userMessage);
                    logger.info("Sent expiration notification to user: {}", task.getUserId());
                }

                if (!adminEmails.isEmpty()) {
                    for (String adminEmail : adminEmails) {
                        String adminSubject = "Admin Alert: " + task.getName();
                        String adminMessage = String.format("Admin Alert: Task '%s' (ID: %s) assigned to user %s has expired. The deadline was %s.",
                                task.getName(), task.getTaskId(), task.getUserId(), task.getDeadline());
                        SnsUtils.sendEmailNotification(taskExpirationAdminNotificationTopicArn, adminEmail, adminSubject, adminMessage);
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
package com.amalitechtaskmanager.handlers.notification;

import com.amalitechtaskmanager.utils.NotificationResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amalitechtaskmanager.utils.CognitoUtils;
import com.amalitechtaskmanager.utils.DynamoDbUtils;
import com.amalitechtaskmanager.utils.SnsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;
import java.util.Optional;

public class ReminderProcessorLambda implements RequestHandler<ScheduledEvent, NotificationResponse> {

    private static final Logger logger = LoggerFactory.getLogger(ReminderProcessorLambda.class);
    private static final String USER_POOL_ID = System.getenv("USER_POOL_ID");
    private static final String TABLE_NAME = System.getenv("TABLE_NAME");
    private static final String SNS_TOPIC_ARN = System.getenv("SNS_TOPIC_ARN");
    private static final String ACTIVE_STATUS = "OPEN";

    private final DynamoDbClient dynamoDbClient;
    private final CognitoIdentityProviderClient cognitoClient;

    public ReminderProcessorLambda() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.cognitoClient = CognitoIdentityProviderClient.create();
    }

    @Override
    public NotificationResponse handleRequest(ScheduledEvent event, Context context) {
        if (USER_POOL_ID == null || TABLE_NAME == null || SNS_TOPIC_ARN == null) {
            logger.error("Missing required environment variables.");
            return new NotificationResponse(false, "Missing required environment variables.");
        }
        logger.info("Processing reminder event: {}", event);

        Optional<String> taskIdOpt = getTaskIdFromEvent(event);

        if (taskIdOpt.isEmpty()) {
            logger.error("Missing taskId in event payload.");
            return new NotificationResponse(false, "Missing taskId in event payload.");
        }

        String taskId = taskIdOpt.get();
        logger.info("Processing reminder for taskId: {}", taskId);

        Optional<Map<String, AttributeValue>> taskOpt = DynamoDbUtils.getTask(dynamoDbClient, TABLE_NAME, taskId);
        logger.info("Task found: {}", taskOpt.isPresent());
        if (taskOpt.isEmpty()) {
            logger.error("Task not found for taskId: {}", taskId);
            return new NotificationResponse(false, "Task not found for taskId: " + taskId);
        }

        Map<String, AttributeValue> taskItem = taskOpt.get();
        String status = Optional.ofNullable(taskItem.get("status"))
                .map(AttributeValue::s)
                .orElse("unknown");

        if (!ACTIVE_STATUS.equalsIgnoreCase(status)) {
            logger.warn("Task is not active for taskId: {}, status: {}", taskId, status);
            return new NotificationResponse(false, "Task is not active for taskId: " + taskId);
        }

        Optional<String> userIdOpt = Optional.ofNullable(taskItem.get("userId")).map(AttributeValue::s);
        Optional<String> titleOpt = Optional.ofNullable(taskItem.get("title")).map(AttributeValue::s);
        Optional<String> deadlineOpt = Optional.ofNullable(taskItem.get("deadline")).map(AttributeValue::s);

        if (userIdOpt.isEmpty() || deadlineOpt.isEmpty()) {
            logger.error("Missing userId or deadline for taskId: {}", taskId);
            return new NotificationResponse(false, "Missing userId or deadline for taskId: " + taskId);
        }

        String userId = userIdOpt.get();
        String title = titleOpt.orElse("Untitled");
        String deadline = deadlineOpt.get();

        Optional<String> emailOpt = CognitoUtils.getUserEmail(cognitoClient, USER_POOL_ID, userId);
        if (emailOpt.isEmpty()) {
            logger.error("No email found for userId: {}", userId);
            return new NotificationResponse(false, "No email found for assigneeId: " + userId);
        }
        String message = String.format(
                "Heading: Task Deadline Reminder\n" +
                        "Task ID: %s\n" +
                        "Task Title: %s\n" +
                        "Assigned To: %s\n" +
                        "Due Date: %s\n" +
                        "Priority: %s\n\n" +
                        "Reminder: The task '%s' assigned to you is due soon. Please ensure all deliverables are completed on time.",
                taskId,
                taskOpt.get().get("name").s(),
                taskOpt.get().get("userId").s(),
                taskOpt.get().get("deadline").s(),
                "High",
                taskOpt.get().get("name").s()
        );


        String subject = "Task Deadline Reminder";
        SnsUtils.sendEmailNotification(SNS_TOPIC_ARN, emailOpt.get(), subject, message);
        return new NotificationResponse(true, "Notification sent successfully.");
    }

    private Optional<String> getTaskIdFromEvent(ScheduledEvent event) {
        try {
            Map<String, Object> eventDetail = (Map<String, Object>) event.getDetail();
            logger.info("Event detail: {}", eventDetail);
            return Optional.ofNullable(eventDetail)
                    .map(detail -> detail.get("taskId"))
                    .map(Object::toString);
        } catch (Exception e) {
            logger.error("Error extracting taskId from event: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

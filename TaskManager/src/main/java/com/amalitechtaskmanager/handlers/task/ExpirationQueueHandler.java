package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.utils.SnsUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Lambda function that processes task expiration events from SQS.
 * This provides reliable processing of expiration notifications.
 */
public class ExpirationQueueHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(ExpirationQueueHandler.class);
    
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String taskExpirationNotificationTopicArn;
    
    /**
     * Default constructor used by Lambda runtime.
     */
    public ExpirationQueueHandler() {
        this.snsClient = SnsClient.create();
        this.taskExpirationNotificationTopicArn = System.getenv("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
    }
    
    /**
     * Constructor for testing with dependency injection.
     */
    public ExpirationQueueHandler(SnsClient snsClient) {
        this.snsClient = snsClient;
        this.taskExpirationNotificationTopicArn = System.getProperty("TASK_EXPIRATION_NOTIFICATION_TOPIC_ARN");
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
                SnsUtils.sendAdminExpirationNotification(snsClient, taskExpirationNotificationTopicArn, task);
                logger.info("Sent expiration notification to admin for task: {}", task.getTaskId());
            } else {
                logger.warn("Notification topic not configured");
            }
        } catch (Exception e) {
            logger.error("Error processing notifications for task {}: {}", task.getTaskId(), e.getMessage(), e);
        }
    }
}

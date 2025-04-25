package com.amalitechtaskmanager.utils;

import com.amalitechtaskmanager.factories.SNSFactory;
import com.amalitechtaskmanager.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.Map;

public class SnsUtils {

    private static final Logger logger = LoggerFactory.getLogger(SnsUtils.class);

    /**
     * Sends a reminder notification for a task
     */
//    public static void sendNotification(SnsClient snsClient, String topicArn, String email, String title, String deadline, String taskId) {
//        try {
//            String message = String.format("Reminder: Task '%s' (ID: %s) is due in 1 hour at %s.", title, taskId, deadline);
//            PublishRequest request = PublishRequest.builder()
//                    .message(message)
//                    .subject("Task Reminder")
//                    .topicArn(topicArn)
//                    .build();
//
//            snsClient.publish(request);
//            logger.info("Notification sent to {} for taskId: {}", email, taskId);
//        } catch (Exception e) {
//            logger.error("Failed to send notification for taskId {}: {}", taskId, e.getMessage());
//        }
//    }

    public static void sendEmailNotification(String topicArn, String email, String subject, String message) {
        try {
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();

            // Use "user_id" to match the filter policy
            messageAttributes.put("recipient_email",
                    MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(email)
                            .build());

            // Publish with message attributes
            SNSFactory.getSnsClient().publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(message)
                    .subject(subject)
                    .messageAttributes(messageAttributes)
                    .build());

        } catch (Exception e) {
            logger.error("Failed to send notification: {}", e.getMessage());
        }
    }
    
    /**
     * Sends an expiration notification to admins
     * 
     * @param snsClient The SNS client
     * @param topicArn The topic ARN to publish to
     * @param task The expired task
     */
    public static void sendAdminExpirationNotification(SnsClient snsClient, String topicArn, Task task) {
        try {
            String message = String.format("Admin Alert: Task '%s' (ID: %s) assigned to user %s has expired. The deadline was %s.", 
                    task.getName(), task.getTaskId(), task.getUserId(), task.getDeadline());
            
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            messageAttributes.put("for_admin", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("true")
                    .build());
            
            PublishRequest request = PublishRequest.builder()
                    .message(message)
                    .subject("Admin Alert: Task Expired")
                    .topicArn(topicArn)
                    .messageAttributes(messageAttributes)
                    .build();

            snsClient.publish(request);
            logger.info("Admin expiration notification sent for taskId: {}", task.getTaskId());
        } catch (Exception e) {
            logger.error("Failed to send admin expiration notification for taskId {}: {}", 
                    task.getTaskId(), e.getMessage());
        }
    }

}

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
            logger.info("messageAttributes" + messageAttributes);
            // Publish with message attributes
            SNSFactory.getSnsClient().publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(message)
                    .subject(subject)
                    .messageAttributes(messageAttributes)
                    .build());
            logger.info("Notification sent to {} for taskId: {}", email, message);
        } catch (Exception e) {
            logger.error("Failed to send notification: {}", e.getMessage());
        }
    }


    /**
     * Send expiration email notification.
     *
     * @param snsClient the sns client
     * @param topicArn  the topic arn
     * @param email     the email
     * @param subject   the subject
     * @param message   the message
     */
    public static void sendExpirationEmailNotification(SnsClient snsClient, String topicArn, String email, String subject, String message) {
        try {
            Map<String, MessageAttributeValue> messageAttribute = new HashMap<>();
            messageAttribute.put("recipient_email",
                    MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(email)
                            .build());
            logger.info("messageToBeSent: {}", messageAttribute);

            snsClient.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(message)
                    .subject(subject)
                    .messageAttributes(messageAttribute)
                    .build());
            logger.info("Email Notification sent to {}: {}", email, message);
        } catch (Exception e) {
            logger.error("Failed to send notification to {}: {}", email, e.getMessage());
        }
    }

}

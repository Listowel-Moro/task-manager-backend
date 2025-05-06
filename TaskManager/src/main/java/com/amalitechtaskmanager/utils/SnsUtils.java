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

    public static void sendEmailNotification(String topicArn, String email, String subject, String message) {
        try {

            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();

            // Use "user_id" to match the filter policy
            messageAttributes.put("recipient_email",
                    MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(email)
                            .build());
            logger.info("messageAttributes{}", messageAttributes);
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
}

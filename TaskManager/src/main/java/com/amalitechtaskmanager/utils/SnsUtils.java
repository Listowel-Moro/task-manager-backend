package com.amalitechtaskmanager.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class SnsUtils {

    private static final Logger logger = LoggerFactory.getLogger(SnsUtils.class);

    public static void sendNotification(SnsClient snsClient, String topicArn, String email, String title, String deadline, String taskId) {
        try {
            String message = String.format("Reminder: Task '%s' (ID: %s) is due in 1 hour at %s.", title, taskId, deadline);
            PublishRequest request = PublishRequest.builder()
                    .message(message)
                    .subject("Task Reminder")
                    .topicArn(topicArn)
                    .build();

            snsClient.publish(request);
            logger.info("Notification sent to {} for taskId: {}", email, taskId);
        } catch (Exception e) {
            logger.error("Failed to send notification for taskId {}: {}", taskId, e.getMessage());
        }
    }
}

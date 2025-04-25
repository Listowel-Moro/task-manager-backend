package com.amalitechtaskmanager.handlers.task;

import java.util.HashMap;
import java.util.Map;

import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.model.Task;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class ProcessTaskAssignmentHandler implements RequestHandler<SQSEvent, Void> {

    private final SnsClient snsClient = SnsClient.create();
    private final ObjectMapper objectMapper = ObjectMapperFactory.getMapper();
    private final String taskNotificationTopicArn = System.getenv("SNS_TOPIC_ARN");
    private final String taskNotificationTopicArnCopy = "arn:aws:sns:eu-central-1:443370714528:TaskAssignmentNotificationTopic";



    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        context.getLogger().log("sns topic arn  ::::::"+ taskNotificationTopicArn);
        for (SQSMessage message : event.getRecords()) {
            try {
                Task taskAssignment = objectMapper.readValue(message.getBody(), Task.class);
                String userEmail = taskAssignment.getUserId();

                if (userEmail != null && !userEmail.isEmpty()) {
                    // Add message attributes for filtering - MATCH EXACTLY WITH FILTER POLICY
                    Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();

                    // Use "user_id" to match the filter policy
                    messageAttributes.put("recipient_email",
                            MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(userEmail)
                                    .build());

                    context.getLogger().log("recipient email " +userEmail);

                    // Create a human-readable message for the notification
                    String messageText = "New task assigned: " + taskAssignment.getName() +
                            "\nDeadline: " + taskAssignment.getDeadline() +
                            "\nDescription: " + taskAssignment.getDescription();


                    context.getLogger().log("topic arn" + taskNotificationTopicArn);

                    // Publish with message attributes
                    snsClient.publish(PublishRequest.builder()
                            .topicArn(taskNotificationTopicArnCopy)
                            .message(messageText)
                            .subject("New Task Assignment: " + taskAssignment.getName())
                            .messageAttributes(messageAttributes)
                            .build());


                    context.getLogger().log("Publishing message with attributes: " + messageAttributes);
                    context.getLogger().log("Message: " + messageText);
                    context.getLogger().log("Published to SNS topic with user_id filter: " + userEmail);
                } else {
                    context.getLogger().log("Skipping task with missing userId: " + taskAssignment.getTaskId());
                }
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return null;
    }
}
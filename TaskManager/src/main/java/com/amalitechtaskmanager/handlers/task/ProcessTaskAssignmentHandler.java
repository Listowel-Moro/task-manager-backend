package com.amalitechtaskmanager.handlers.task;

import java.util.HashMap;
import java.util.Map;

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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String taskNotificationTopicArn = System.getenv("SNS_TOPIC_ARN");
    
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSMessage message : event.getRecords()) {
            try {
                Task taskAssignment = objectMapper.readValue(message.getBody(), Task.class);
                String userId = taskAssignment.getUserId();
                
                if (userId != null && !userId.isEmpty()) {
                    // Process task assignment in the system
                    // Send notification to the FIFO topic with user_id as message attribute for filtering
                    Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
                    messageAttributes.put("user_id", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(userId)
                            .build());
                    
                    // Publish to the notification topic with filtering attributes
                    snsClient.publish(PublishRequest.builder()
                            .topicArn(taskNotificationTopicArn)
                            .message(objectMapper.writeValueAsString(taskAssignment))
                            .messageAttributes(messageAttributes)
                            .messageGroupId(userId)
                            .messageDeduplicationId(taskAssignment.getTaskId())
                            .build());
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
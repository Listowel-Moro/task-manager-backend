package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.model.Task;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class ProcessTaskAssignmentHandler implements RequestHandler<SQSEvent, Void> {

    private final SnsClient snsClient = SnsClient.create();
    private final ObjectMapper objectMapper = ObjectMapperFactory.getMapper();
    private final String taskNotificationTopicArn = System.getenv("SNS_TOPIC_ARN");

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSMessage message : event.getRecords()) {
            try {
                Task taskAssignment = objectMapper.readValue(message.getBody(), Task.class);
                String userId = taskAssignment.getUserId();

                if (userId != null && !userId.isEmpty()) {
                    // Process task assignment in the system
                    // Publish to the SNS topic without message attributes to send to all subscribers
                    snsClient.publish(PublishRequest.builder()
                            .topicArn(taskNotificationTopicArn)
                            .message(objectMapper.writeValueAsString(taskAssignment))
                            .build());
                    context.getLogger().log("Published to SNS topic: " + taskNotificationTopicArn + " for task: " + taskAssignment.getTaskId());
                } else {
                    context.getLogger().log("Skipping task with missing userId: " + taskAssignment.getTaskId());
                }
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
            }
        }
        return null;
    }
}
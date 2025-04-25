package com.amalitechtaskmanager.handlers.task;

import java.time.format.DateTimeFormatter;

import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.model.Task;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.amalitechtaskmanager.utils.SnsUtils.sendEmailNotification;

public class ProcessTaskAssignmentHandler implements RequestHandler<SQSEvent, Void> {

    private final ObjectMapper objectMapper = ObjectMapperFactory.getMapper();
    private final String taskNotificationTopicArn = System.getenv("SNS_TOPIC_ARN");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy HH:mm");

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        context.getLogger().log("Processing task assignments with SNS topic ARN: " + taskNotificationTopicArn);
        
        for (SQSMessage message : event.getRecords()) {
            try {
                Task task = objectMapper.readValue(message.getBody(), Task.class);
                String userEmail = task.getUserId();

                if (userEmail != null && !userEmail.isEmpty()) {
                    String subject = "New Task Assignment: " + task.getName();
                    String messageText = createEmailBody(task);
                    
                    sendEmailNotification(taskNotificationTopicArn, userEmail, subject, messageText);
                    context.getLogger().log("Successfully sent task assignment notification to: " + userEmail);
                } else {
                    context.getLogger().log("Warning: Skipping task with missing userId: " + task.getTaskId());
                }
            } catch (Exception e) {
                context.getLogger().log("Error processing task assignment: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return null;
    }


    private String createEmailBody(Task task) {
        return String.format("""
            Dear Team Member,
            
            A new task has been assigned to you:
            
            Task Details:
            -------------
            Name: %s
            ID: %s
            Status: %s
            
            Description:
            -----------
            %s
            
            Timeline:
            ---------
            Assigned Date: %s
            Deadline: %s
            
            Action Required:
            ---------------
            1. Review the task details thoroughly
            2. Begin work on the task according to its priority
            3. Update the task status as you progress
            4. Complete the task before the deadline
            
            Important Notes:
            ---------------
            • If you need any clarification, please contact your supervisor
            • Regular updates on task progress are expected
            
            You can access the task management system to:
            • View complete task details
            • Update task status
            • Add comments or questions
            
            Best regards,
            Task Management System
            """,
            task.getName(),
            task.getTaskId(),
            task.getStatus().toString(),
            task.getDescription() != null ? task.getDescription() : "No description provided",
            task.getCreatedAt() != null ? task.getCreatedAt().format(dateFormatter) : "Not specified",
            task.getDeadline() != null ? task.getDeadline().format(dateFormatter) : "Not specified"
        );
    }
}

package com.amalitechtaskmanager.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.ScheduleState;
import software.amazon.awssdk.services.scheduler.model.Target;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amalitechtaskmanager.model.Task;

public class SchedulerUtils {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerUtils.class);
    private final SchedulerClient schedulerClient;

    public SchedulerUtils(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    public static Optional<String> getAttributeValue(AttributeValue attr) {
        if (attr == null) return Optional.empty();
        return Optional.ofNullable(attr.getS()).filter(s -> !s.isEmpty());
    }

    public static Optional<OffsetDateTime> parseDeadline(String deadline, String taskId) {
        try {
            return Optional.of(OffsetDateTime.parse(deadline, DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } catch (DateTimeParseException e) {
            logger.error("Invalid deadline format for taskId: {}: {}", taskId, deadline);
            return Optional.empty();
        }
    }

    public void deleteSchedule(String taskId) {
        try {
            DeleteScheduleRequest request = DeleteScheduleRequest.builder()
                    .name("TaskReminder_" + taskId)
                    .build();
            schedulerClient.deleteSchedule(request);
            logger.info("Deleted schedule for taskId: {}", taskId);
        } catch (ResourceNotFoundException e) {
            logger.debug("No schedule found to delete for taskId: {}", taskId);
        } catch (Exception e) {
            logger.error("Error deleting schedule for taskId: {}: {}", taskId, e.getMessage());
        }
    }

    public void createSchedule(String taskId, OffsetDateTime reminderTime,
                               Map<String, AttributeValue> taskItem,
                               String targetLambdaArn, String schedulerRoleArn) {
        try {
            String scheduleExpression = "at(" + reminderTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ")";
            Map<String, String> inputPayload = new HashMap<>();
            taskItem.forEach((key, value) ->
                    getAttributeValue(value).ifPresent(val -> inputPayload.put(key, val))
            );

            CreateScheduleRequest request = CreateScheduleRequest.builder()
                    .name("TaskReminder_" + taskId)
                    .scheduleExpression(scheduleExpression)
                    .state(ScheduleState.ENABLED)
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode("OFF").build())
                    .target(Target.builder()
                            .arn(targetLambdaArn)
                            .roleArn(schedulerRoleArn)
                            .input(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inputPayload))
                            .build())
                    .build();

            schedulerClient.createSchedule(request);
            logger.info("Created new schedule for taskId: {} at {}", taskId, reminderTime);
        } catch (Exception e) {
            logger.error("Failed to create schedule for taskId: {}: {}", taskId, e.getMessage());
        }
    }
    
    /**
     * Schedules a task expiration check at the task's deadline
     * 
     * @param task The task to schedule expiration for
     * @param expirationLambdaArn The ARN of the lambda to trigger for expiration
     * @param schedulerRoleArn The ARN of the role to use for scheduling
     * @return true if scheduling was successful, false otherwise
     */
    public boolean scheduleTaskExpiration(Task task, String expirationLambdaArn, String schedulerRoleArn) {
        if (task == null || task.getDeadline() == null || task.getTaskId() == null) {
            logger.warn("Cannot schedule expiration for invalid task");
            return false;
        }
        
        try {
            // Convert task deadline to OffsetDateTime
            OffsetDateTime expirationTime = task.getDeadline().atOffset(ZoneOffset.UTC);
            OffsetDateTime now = OffsetDateTime.now();
            
            // Don't schedule if deadline is in the past
            if (expirationTime.isBefore(now)) {
                logger.warn("Task deadline {} is in the past for taskId: {}", expirationTime, task.getTaskId());
                return false;
            }
            
            // Convert task to a map for the scheduler payload
            Map<String, String> inputPayload = new HashMap<>();
            inputPayload.put("taskId", task.getTaskId());
            inputPayload.put("name", task.getName());
            inputPayload.put("description", task.getDescription() != null ? task.getDescription() : "");
            inputPayload.put("status", task.getStatus().toString());
            inputPayload.put("deadline", task.getDeadline().toString());
            inputPayload.put("userId", task.getUserId());
            
            String scheduleExpression = "at(" + expirationTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ")";
            
            CreateScheduleRequest request = CreateScheduleRequest.builder()
                    .name("TaskExpiration_" + task.getTaskId())
                    .scheduleExpression(scheduleExpression)
                    .state(ScheduleState.ENABLED)
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode("OFF").build())
                    .target(Target.builder()
                            .arn(expirationLambdaArn)
                            .roleArn(schedulerRoleArn)
                            .input(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inputPayload))
                            .build())
                    .build();
            
            schedulerClient.createSchedule(request);
            logger.info("Created expiration schedule for taskId: {} at deadline: {}", task.getTaskId(), expirationTime);
            return true;
        } catch (Exception e) {
            logger.error("Failed to schedule expiration for taskId: {}: {}", task.getTaskId(), e.getMessage());
            return false;
        }
    }
    
    /**
     * Deletes a task expiration schedule
     * 
     * @param taskId The ID of the task
     */
    public void deleteExpirationSchedule(String taskId) {
        try {
            DeleteScheduleRequest request = DeleteScheduleRequest.builder()
                    .name("TaskExpiration_" + taskId)
                    .build();
            schedulerClient.deleteSchedule(request);
            logger.info("Deleted expiration schedule for taskId: {}", taskId);
        } catch (ResourceNotFoundException e) {
            logger.debug("No expiration schedule found to delete for taskId: {}", taskId);
        } catch (Exception e) {
            logger.error("Error deleting expiration schedule for taskId: {}: {}", taskId, e.getMessage());
        }
    }
}

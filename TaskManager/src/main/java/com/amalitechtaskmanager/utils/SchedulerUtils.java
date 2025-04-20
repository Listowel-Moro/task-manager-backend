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

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

public class SchedulerUtils {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerUtils.class);
    private final SchedulerClient schedulerClient;

    public SchedulerUtils(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    public static Optional<String> getAttributeValue(AttributeValue attr) {
        return Optional.ofNullable(attr).map(AttributeValue::getS);
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
}
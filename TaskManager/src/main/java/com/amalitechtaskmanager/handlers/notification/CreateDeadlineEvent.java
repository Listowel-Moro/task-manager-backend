package com.amalitechtaskmanager.handlers.notification;

import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.utils.DynamoDbUtils;
import com.amalitechtaskmanager.utils.NotificationResponse;
import com.amalitechtaskmanager.utils.SchedulerUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.scheduler.SchedulerClient;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import java.util.*;

public class CreateDeadlineEvent implements RequestHandler<DynamodbEvent, NotificationResponse> {

    private static final Logger logger = LoggerFactory.getLogger(CreateDeadlineEvent.class);

    private static final String TARGET_LAMBDA_ARN = System.getenv("TARGET_LAMBDA_ARN");
    private static final String SCHEDULER_ROLE_ARN = System.getenv("SCHEDULER_ROLE_ARN");
    private static final long REMINDER_OFFSET_MINUTES = 2;

    private final SchedulerUtils schedulerUtils;


    public CreateDeadlineEvent() {
        SchedulerClient schedulerClient = SchedulerClient.create();
        this.schedulerUtils = new SchedulerUtils(schedulerClient);
    }


    @Override
    public NotificationResponse handleRequest(DynamodbEvent event, Context context) {
        if (TARGET_LAMBDA_ARN == null || SCHEDULER_ROLE_ARN == null) {
            logger.error("Environment variables TARGET_LAMBDA_ARN or SCHEDULER_ROLE_ARN are not set");
            return new NotificationResponse(false, "Environment variables TARGET_LAMBDA_ARN or SCHEDULER_ROLE_ARN are not set");
        }
        List<String> errors = new ArrayList<>();
        int processedRecords = 0;

        for (DynamodbStreamRecord record : event.getRecords()) {
            String eventName = record.getEventName();
            if (!"INSERT".equals(eventName)) {
                logger.warn("Skipping non-INSERT event");
                continue;
            }

            try {
                Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
                if (newImage == null) {
                    errors.add("No newImage found for " + eventName + " event");
                    logger.warn("No newImage found for " + eventName + " event");
                    continue;
                }

                Optional<Task> optionalTask = DynamoDbUtils.parseTask(newImage);
                optionalTask.ifPresentOrElse(
                        task -> logger.info("Parsed task: {}", task),
                        () -> logger.warn("Failed to parse task object from record")
                );
                if (optionalTask.isEmpty()) {
                    errors.add("Failed to parse task object from record");
                    logger.warn("Failed to parse task object from record");
                    continue;
                }

                Task task = optionalTask.get();
                if (task.getTaskId() == null) {
                    errors.add("taskId missing in task object");
                    logger.warn("taskId missing in task object");
                    continue;
                }

                LocalDateTime deadline = task.getDeadline();
                if (deadline == null) {
                    errors.add("No deadline found for taskId: " + task.getTaskId());
                    logger.warn("No deadline found for taskId: " + task.getTaskId());
                    continue;
                }

                OffsetDateTime reminderTime = deadline.atOffset(ZoneOffset.UTC).minusMinutes(REMINDER_OFFSET_MINUTES);
                OffsetDateTime now = OffsetDateTime.now();

                if (reminderTime.isBefore(now)) {
                   errors.add("Reminder time " + reminderTime + " is in the past for taskId: " + task.getTaskId());
                   logger.warn("Reminder time {} is in the past for taskId: {}", reminderTime, task.getTaskId());
                   continue;
                }
                logger.info("Creating schedule for taskId: {} at {}", task.getTaskId(), reminderTime);
                schedulerUtils.createSchedule(task.getTaskId(),reminderTime, newImage, TARGET_LAMBDA_ARN, SCHEDULER_ROLE_ARN);
                processedRecords++;

            } catch (Exception e) {
                logger.error("Error processing record: {}", e.getMessage(), e);
            }
        }

        if(!errors.isEmpty()) {
            return new NotificationResponse(false, "Processed " + processedRecords + " records with " + errors.size() + " errors: " + String.join("; ", errors));
        }

        return new NotificationResponse(true, "Successfully processed " + processedRecords + " records.");
    }
}

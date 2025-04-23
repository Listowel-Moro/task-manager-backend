package com.amalitechtaskmanager.handlers.notification;

import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.utils.DynamoDbUtils;
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

public class CreateDeadlineEvent implements RequestHandler<DynamodbEvent, Optional<Void>> {

    private static final Logger logger = LoggerFactory.getLogger(CreateDeadlineEvent.class);

    private static final String TARGET_LAMBDA_ARN = System.getenv("TARGET_LAMBDA_ARN");
    private static final String SCHEDULER_ROLE_ARN = System.getenv("SCHEDULER_ROLE_ARN");
    private static final long REMINDER_OFFSET_MINUTES = 60;

    private final SchedulerClient schedulerClient;
    private final SchedulerUtils schedulerUtils;

    public CreateDeadlineEvent(SchedulerUtils schedulerUtils) {
        this.schedulerUtils = schedulerUtils;
        this.schedulerClient = SchedulerClient.create();
    }

    @Override
    public Optional<Void> handleRequest(DynamodbEvent event, Context context) {
        if (TARGET_LAMBDA_ARN == null || SCHEDULER_ROLE_ARN == null) {
            logger.error("Environment variables TARGET_LAMBDA_ARN or SCHEDULER_ROLE_ARN are not set");
            throw new IllegalStateException("Required environment variables are not set");
        }
        for (DynamodbStreamRecord record : event.getRecords()) {
            String eventName = record.getEventName();
            if (!"INSERT".equals(eventName) && !"MODIFY".equals(eventName)) {
                logger.debug("Skipping event: {}", eventName);
                continue;
            }

            try {
                Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
                if (newImage == null) {
                    logger.warn("No newImage found for {} event", eventName);
                    continue;
                }

                Optional<Task> optionalTask = DynamoDbUtils.parseTask(newImage);
                if (optionalTask.isEmpty()) {
                    logger.warn("Failed to parse task object from record");
                    continue;
                }

                Task task = optionalTask.get();
                if (task.getTaskId() == null) {
                    logger.warn("taskId missing in task object");
                    continue;
                }

                logger.info("Processing {} event for taskId: {}", eventName, task.getTaskId());

                LocalDateTime deadline = task.getDeadline();
                if (deadline == null) {
                    logger.warn("No deadline found for taskId: {}", task.getTaskId());
                    continue;
                }

                OffsetDateTime reminderTime = deadline.atOffset(ZoneOffset.UTC).minusMinutes(REMINDER_OFFSET_MINUTES);
                OffsetDateTime now = OffsetDateTime.now();

                if (reminderTime.isBefore(now)) {
                    logger.warn("Reminder time {} is in the past for taskId: {}", reminderTime, task.getTaskId());
                    continue;
                }

                logger.info("Creating schedule for taskId: {} at {}", task.getTaskId(), reminderTime);

                schedulerUtils.createSchedule(task.getTaskId(),reminderTime, newImage, TARGET_LAMBDA_ARN, SCHEDULER_ROLE_ARN);

                logger.debug("Record details: {}", newImage);

            } catch (Exception e) {
                logger.error("Error processing record: {}", e.getMessage(), e);
            }
        }

        return Optional.empty();
    }
}

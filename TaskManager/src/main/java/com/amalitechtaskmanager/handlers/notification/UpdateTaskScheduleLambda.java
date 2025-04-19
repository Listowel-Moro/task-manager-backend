package com.amalitechtaskmanager.handlers.notification;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amalitechtaskmanager.utils.SchedulerUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.scheduler.SchedulerClient;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

public class UpdateTaskScheduleLambda implements RequestHandler<DynamodbEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(UpdateTaskScheduleLambda.class);
    private static final String TARGET_LAMBDA_ARN = System.getenv("TARGET_LAMBDA_ARN");
    private static final String SCHEDULER_ROLE_ARN = System.getenv("SCHEDULER_ROLE_ARN");
    private static final long REMINDER_OFFSET_MINUTES = 60;
    private static final String ACTIVE_STATUS = "active";

    private final SchedulerUtils schedulerUtils;

    public UpdateTaskScheduleLambda() {
        SchedulerClient schedulerClient = SchedulerClient.create();
        this.schedulerUtils = new SchedulerUtils(schedulerClient);
    }

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        // Validate environment variables
        if (TARGET_LAMBDA_ARN == null || SCHEDULER_ROLE_ARN == null) {
            logger.error("Environment variables TARGET_LAMBDA_ARN or SCHEDULER_ROLE_ARN are not set");
            throw new IllegalStateException("Required environment variables are not set");
        }

        event.getRecords().stream()
                .filter(record -> "MODIFY".equals(record.getEventName()))
                .forEach(this::processRecord);

        return null;
    }

    private void processRecord(DynamodbStreamRecord record) {
        try {
            Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
            Map<String, AttributeValue> oldImage = record.getDynamodb().getOldImage();

            Optional<String> taskId = SchedulerUtils.getAttributeValue(newImage.get("taskId"));
            if (taskId.isEmpty()) {
                logger.error("taskId is missing for event: {}", record);
                return;
            }
            logger.info("Processing MODIFY event for taskId: {}", taskId.get());

            Optional<String> newStatus = SchedulerUtils.getAttributeValue(newImage.get("status"));
            if (!newStatus.map(ACTIVE_STATUS::equals).orElse(false)) {
                schedulerUtils.deleteSchedule(taskId.get());
                logger.info("Task status {} for taskId: {}; deleted schedule", newStatus.orElse("unknown"), taskId.get());
                return;
            }

            Optional<String> newDeadline = SchedulerUtils.getAttributeValue(newImage.get("deadline"));
            Optional<String> oldDeadline = SchedulerUtils.getAttributeValue(oldImage.get("deadline"));
            Optional<String> newAssigneeId = SchedulerUtils.getAttributeValue(newImage.get("assigneeId"));
            Optional<String> oldAssigneeId = SchedulerUtils.getAttributeValue(oldImage.get("assigneeId"));

            if (newDeadline.isEmpty()) {
                logger.warn("Missing deadline for taskId: {}", taskId.get());
                schedulerUtils.deleteSchedule(taskId.get());
                return;
            }

            boolean deadlineChanged = !newDeadline.equals(oldDeadline);
            boolean assigneeChanged = !newAssigneeId.equals(oldAssigneeId);

            if (!deadlineChanged && !assigneeChanged) {
                logger.debug("No relevant changes for taskId: {}", taskId.get());
                return;
            }

            Optional<OffsetDateTime> deadline = SchedulerUtils.parseDeadline(newDeadline.get(), taskId.get());
            if (deadline.isEmpty()) {
                return;
            }

            OffsetDateTime reminderTime = deadline.get().minusMinutes(REMINDER_OFFSET_MINUTES);
            OffsetDateTime now = OffsetDateTime.now();

            if (reminderTime.isBefore(now)) {
                logger.warn("Reminder time {} is in the past for taskId: {}", reminderTime, taskId.get());
                schedulerUtils.deleteSchedule(taskId.get());
                return;
            }

            schedulerUtils.deleteSchedule(taskId.get());
            schedulerUtils.createSchedule(taskId.get(), reminderTime, newImage, TARGET_LAMBDA_ARN, SCHEDULER_ROLE_ARN);

        } catch (Exception e) {
            logger.error("Error processing MODIFY event for taskId: {}: {}",
                    record.getDynamodb().getKeys().get("taskId").getS(), e.getMessage());
        }
    }
}
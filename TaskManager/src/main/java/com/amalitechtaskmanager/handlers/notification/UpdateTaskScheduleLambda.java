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

public class UpdateTaskScheduleLambda implements RequestHandler<DynamodbEvent, NotificationResponse> {

    private static final Logger logger = LoggerFactory.getLogger(UpdateTaskScheduleLambda.class);
    private static final String TARGET_LAMBDA_ARN = System.getenv("TARGET_LAMBDA_ARN");
    private static final String SCHEDULER_ROLE_ARN = System.getenv("SCHEDULER_ROLE_ARN");
    private static final long REMINDER_OFFSET_MINUTES = 2;
    private static final String ACTIVE_STATUS = "OPEN";

    private final SchedulerUtils schedulerUtils;

    public UpdateTaskScheduleLambda() {
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
        int updatedCount = 0;

        for (DynamodbStreamRecord record : event.getRecords()) {
            if (!"MODIFY".equals(record.getEventName())) {
                logger.debug("Skipping non-MODIFY event: {}", record.getEventName());
                continue;
            }

            try {
                Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
                Map<String, AttributeValue> oldImage = record.getDynamodb().getOldImage();

                Optional<Task> optionalTask = DynamoDbUtils.parseTask(newImage);
                if (optionalTask.isEmpty()) {
                    logger.warn("Failed to parse task object from newImage");
                    errors.add("Failed to parse task object from newImage");
                    continue;
                }

                Task task = optionalTask.get();
                String taskId = task.getTaskId();
                if (taskId == null) {
                    logger.warn("Missing taskId in task");
                    errors.add("Missing taskId in task");
                    continue;
                }

                logger.info("Processing MODIFY event for taskId: {}", taskId);

                // Check task status
                String status = Optional.ofNullable(newImage.get("status"))
                        .map(AttributeValue::getS)
                        .orElse("unknown");



                Optional<String> newDeadlineStr = SchedulerUtils.getAttributeValue(newImage.get("deadline"));
                Optional<String> oldDeadlineStr = SchedulerUtils.getAttributeValue(oldImage.get("deadline"));
                Optional<String> newAssignee = SchedulerUtils.getAttributeValue(newImage.get("userId"));
                Optional<String> oldAssignee = SchedulerUtils.getAttributeValue(oldImage.get("userId"));

                if (newDeadlineStr.isEmpty()) {
                    logger.warn("Missing deadline for taskId: {}", taskId);
                    schedulerUtils.deleteSchedule(taskId);
                    errors.add("Missing deadline for taskId: " + taskId);
                    continue;
                }

                boolean deadlineChanged = !newDeadlineStr.equals(oldDeadlineStr);
                boolean assigneeChanged = !newAssignee.equals(oldAssignee);

                if (!deadlineChanged && !assigneeChanged) {
                    logger.debug("No relevant changes for taskId: {}", taskId);
                    continue;
                }

                Optional<OffsetDateTime> parsedDeadline = SchedulerUtils.parseDeadline(newDeadlineStr.get());
                if (parsedDeadline.isEmpty()) {
                    errors.add("Invalid deadline for taskId: " + taskId);
                    continue;
                }


                OffsetDateTime reminderTime = parsedDeadline.get().minusMinutes(REMINDER_OFFSET_MINUTES);
                OffsetDateTime now = OffsetDateTime.now();

                if (reminderTime.isBefore(now)) {
                    logger.warn("Reminder time {} is in the past for taskId: {}", reminderTime, taskId);
                    schedulerUtils.deleteSchedule(taskId);
                    errors.add("Reminder time is in the past for taskId: " + taskId);
                    continue;
                }

                if (!ACTIVE_STATUS.equals(status)) {
                    schedulerUtils.deleteSchedule(taskId);
                    logger.info("Deleted schedule for taskId: {} due to status '{}'", taskId, status);
                    continue;
                }

                schedulerUtils.deleteSchedule(taskId);
                logger.info("Deleted previous schedule for taskId: {}", taskId);

                schedulerUtils.createSchedule(taskId, reminderTime, newImage, TARGET_LAMBDA_ARN, SCHEDULER_ROLE_ARN);
                logger.info("Created new schedule for taskId: {} at {}", taskId, reminderTime);
                updatedCount++;

            } catch (Exception e) {
                String taskId = record.getDynamodb().getKeys().containsKey("taskId")
                        ? record.getDynamodb().getKeys().get("taskId").getS()
                        : "unknown";
                logger.error("Exception while processing MODIFY event for taskId: {}: {}", taskId, e.getMessage(), e);
                errors.add("Exception for taskId: " + taskId + " - " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            return new NotificationResponse(false, "Updated " + updatedCount + " records with " + errors.size() + " issues: " + String.join("; ", errors));
        }

        return new NotificationResponse(true, "Successfully updated " + updatedCount + " records.");
    }
}

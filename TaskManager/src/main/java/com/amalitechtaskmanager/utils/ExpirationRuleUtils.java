package com.amalitechtaskmanager.utils;

import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * Utility class for handling task expiration rules
 */
public class ExpirationRuleUtils {

    private static final Logger logger = LoggerFactory.getLogger(ExpirationRuleUtils.class);

    /**
     * Checks if a task should be marked as expired based on its deadline
     *
     * @param task The task to check
     * @return true if the task should be expired, false otherwise
     */
    public static boolean shouldExpireTask(Task task) {
        if (task == null || task.getDeadline() == null) {
            return false;
        }

        // Don't expire tasks that are already completed or expired
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.EXPIRED) {
            return false;
        }

        // Check if the deadline has passed
        return task.getDeadline().isBefore(LocalDateTime.now());
    }

    /**
     * Marks a task as expired if it meets the expiration criteria
     *
     * @param task The task to potentially mark as expired
     * @return true if the task was marked as expired, false otherwise
     */
    public static boolean expireTaskIfNeeded(Task task) {
        if (shouldExpireTask(task)) {
            task.markAsExpired();
            logger.info("Task {} has been marked as expired. Deadline was {}",
                    task.getTaskId(), task.getDeadline());
            return true;
        }
        return false;
    }
}

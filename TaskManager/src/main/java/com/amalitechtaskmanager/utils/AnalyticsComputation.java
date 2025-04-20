package com.amalitechtaskmanager.utils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyticsComputation {
    public static Map<String, Object> computeAnalytics(List<Map<String, Object>> tasks) {
        Map<String, Object> analytics = new HashMap<>();

        long completedTasks = 0;
        long inProgressTasks = 0;
        long deadlinePassedTasks = 0;
        Instant now = Instant.now();

        for (Map<String, Object> task : tasks) {
            String status = (String) task.getOrDefault("status", "");
            String dueDate = (String) task.get("deadline");

            // Count by status
            if ("completed".equalsIgnoreCase(status)) {
                completedTasks++;
            } else if ("open".equalsIgnoreCase(status)) {
                inProgressTasks++;
            }

            // Count deadline passed (not completed and due_date < now)
            if (dueDate != null && !"completed".equalsIgnoreCase(status)) {
                try {
                    Instant due = Instant.parse(dueDate);
                    if (due.isBefore(now)) {
                        deadlinePassedTasks++;
                    }
                } catch (Exception e) {
                    // Skip invalid due_date
                }
            }
        }

        // Build analytics response
        analytics.put("totalTasks", tasks.size());
        analytics.put("completedTasks", completedTasks);
        analytics.put("inProgressTasks", inProgressTasks);
        analytics.put("deadlinePassedTasks", deadlinePassedTasks);

        return analytics;
    }
}

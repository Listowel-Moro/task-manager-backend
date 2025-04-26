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
        long closedTasks = 0;
        long deadlinePassedTasks = 0;

        for (Map<String, Object> task : tasks) {
            String status = (String) task.getOrDefault("status", "");
            String dueDate = (String) task.get("deadline");

            // Count by status
            if ("completed".equalsIgnoreCase(status)) {
                completedTasks++;
            } else if ("closed".equalsIgnoreCase(status)) {
                closedTasks++;
            } else if ("open".equalsIgnoreCase(status)) {
                inProgressTasks++;
            } else if ("expired".equalsIgnoreCase(status)) {
                deadlinePassedTasks++;
            }

        }

        // Build analytics response
        analytics.put("totalTasks", tasks.size());
        analytics.put("completedTasks", completedTasks);
        analytics.put("closedTasks", closedTasks);
        analytics.put("inProgressTasks", inProgressTasks);
        analytics.put("deadlinePassedTasks", deadlinePassedTasks);

        return analytics;
    }
}
package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.model.TaskStatus;
import com.amalitechtaskmanager.utils.AuthorizerUtil;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.utils.TaskUtils;
import java.time.format.DateTimeFormatter;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.SnsUtils.sendEmailNotification;

public class CloseTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final String TABLE_NAME = System.getenv("TASKS_TABLE");
    private static final String TASK_CLOSED_TOPIC_ARN = System.getenv("TASK_CLOSED_TOPIC_ARN");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy HH:mm");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {


        // ensuring only admins can perform such task.
        String idToken = event.getHeaders().get("Authorization");

        if (idToken == null) {
            return createResponse(401, "Unauthorized-Missing Header");
        }

        if (!AuthorizerUtil.authorize(idToken)){
            return createResponse(401, "not authorized to perform this operation");
        }



        try {
            String taskId = event.getPathParameters().get("taskId");
            if (taskId == null) {
                return createResponse(400, "Missing taskId");
            }

            Task task = TaskUtils.getTaskById(taskId, TABLE_NAME);
            if (task == null) {
                return createResponse(404, "Task not found");
            }

            task.setStatus(TaskStatus.CLOSED);
            TaskUtils.updateTask(task, TABLE_NAME);

            // Send notification to task assignee
            String subject = String.format("Task Closed: %s", task.getName());
            String message = createEmailBody(task);
            sendEmailNotification(TASK_CLOSED_TOPIC_ARN, task.getUserId(), subject, message);
            context.getLogger().log("Task closure notification sent to: " + task.getUserId());

            return createResponse(200, "Task closed successfully");
        } catch (Exception e) {
            context.getLogger().log("Error closing task: " + e.getMessage());
            return createResponse(500, "Internal Server Error: " + e.getMessage());
        }
    }

    private String createEmailBody(Task task) {
        return String.format("""
            Dear Team Member,
            
            Your task has been closed successfully:
            
            Task Details:
            -------------
            Name: %s
            ID: %s
            Description: %s
            
            Timeline:
            ---------
            Created: %s
            Original Deadline: %s
            
            Note: If you need to reopen this task or have any questions,
            please contact your supervisor.
            
            Best regards,
            Task Management System
            """,
            task.getName(),
            task.getTaskId(),
            task.getDescription() != null ? task.getDescription() : "No description provided",
            task.getCreatedAt() != null ? task.getCreatedAt().format(dateFormatter) : "Not specified",
            task.getDeadline() != null ? task.getDeadline().format(dateFormatter) : "Not specified"
        );
    }
}

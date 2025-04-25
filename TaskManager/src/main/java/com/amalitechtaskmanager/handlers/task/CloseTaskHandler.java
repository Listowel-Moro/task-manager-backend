package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.model.TaskStatus;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amalitechtaskmanager.model.Task;

import com.amalitechtaskmanager.utils.TaskUtils;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;

public class CloseTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final String TABLE_NAME = System.getenv("TASKS_TABLE");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
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

            return createResponse(200, "Task closed successfully");
        } catch (Exception e) {
            return createResponse(500, "Internal Server Error: " + e.getMessage());
        }
    }
}
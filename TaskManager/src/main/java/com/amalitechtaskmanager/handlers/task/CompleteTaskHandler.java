package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.model.TaskStatus;
import com.amalitechtaskmanager.utils.TaskUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.getCurrentUserEmail;
import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.isUserInAdminGroup;

public class CompleteTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final String TABLE_NAME = System.getenv("TASKS_TABLE");
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String idToken = event.getHeaders().get("Authorization");

            String taskId = event.getPathParameters().get("taskId");
            if (taskId == null) {
                return createResponse(event, 400, "Missing taskId");
            }

            Task task = TaskUtils.getTaskById(taskId, TABLE_NAME);
            if (task == null) {
                return createResponse(event, 404, "Task not found");
            }

            String userId = event.getRequestContext().getIdentity().getUser();
            if (!isUserInAdminGroup(idToken) && !task.getUserId().equals(getCurrentUserEmail(event))) {
                return createResponse(event, 403, "Forbidden - User is not authorized to complete this task");
            }

            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            TaskUtils.updateTask(task, TABLE_NAME);

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("message", "Task completed successfully");
            responseBody.put("taskId", task.getTaskId());

            String body = mapper.writeValueAsString(responseBody);
            return createResponse(event, 200, body);
        } catch (Exception e) {
            return createResponse(event, 500, "Internal Server Error: " + e.getMessage());
        }
    }
}
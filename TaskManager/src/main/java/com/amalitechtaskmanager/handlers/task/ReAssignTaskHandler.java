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
import java.util.Map;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.isUserInAdminGroup;
import static com.amalitechtaskmanager.utils.SnsUtils.sendEmailNotification;


public class ReAssignTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    //private final SnsClient snsClient = SNSClientFactory.getSnsClient();
    public static final String TABLE_NAME = System.getenv("TASKS_TABLE");
    private static final ObjectMapper objectMapper = ObjectMapperFactory.getMapper();
    private final String TASK_ASSIGNMENT_TOPIC_ARN = System.getenv("TASK_ASSIGNMENT_TOPIC_ARN");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String idToken = event.getHeaders().get("Authorization");
            if (!isUserInAdminGroup(idToken)) {
                return createResponse(event, 403, "Forbidden - User is not authorized to perform this operation");
            }

            Map<String, Object> body = objectMapper.readValue(event.getBody(), Map.class);
            String taskId = event.getPathParameters().get("taskId");
            String newUserId = (String) body.get("newAssignee");
            String deadline = (String) body.get("deadline");

            if (taskId == null || newUserId == null) {
                return createResponse(event, 400, "Missing taskId or newAssignee");
            }

            Task task = TaskUtils.getTaskById(taskId, TABLE_NAME);
            if (task == null) {
                return createResponse(event, 404, "Task not found");
            }

            String oldUserId = task.getUserId();

            task.setUserId(newUserId);
            task.setStatus(TaskStatus.OPEN);
            if (deadline != null) {
                task.setDeadline(LocalDateTime.parse(deadline));
            }
            TaskUtils.updateTask(task, TABLE_NAME);

            // Send notification to new assignee
            String newAssigneeMessage = createNewAssigneeEmail(task, oldUserId);
            String newAssigneeSubject = "New Task Assignment: " + task.getName();
            sendEmailNotification(TASK_ASSIGNMENT_TOPIC_ARN, newUserId, newAssigneeSubject, newAssigneeMessage);

            // Send notification to previous assignee
            String previousAssigneeMessage = createPreviousAssigneeEmail(task, newUserId);
            String previousAssigneeSubject = "Task Reassignment: " + task.getName();
            sendEmailNotification(TASK_ASSIGNMENT_TOPIC_ARN, oldUserId, previousAssigneeSubject, previousAssigneeMessage);

            Map<String, Object> responseBody = Map.of(
                    "message", "Task reassigned successfully",
                    "taskId", task.getTaskId(),
                    "newAssignee", newUserId,
                    "oldAssignee", oldUserId
            );
            String data = objectMapper.writeValueAsString(responseBody);

            return createResponse(event, 200, data);

        } catch (Exception e) {
            e.printStackTrace();
            return createResponse(event, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private String createNewAssigneeEmail(Task task, String previousAssignee) {
        return String.format("""
            Dear Team Member,
            
            A task has been assigned to you:
            
            Task Details:
            -------------
            Name: %s
            ID: %s
            Description: %s
            Status: %s
            Deadline: %s
            
            Action Required:
            ---------------
            Please review this task and begin work on it at your earliest convenience. 
            If you have any questions or concerns about this assignment, please contact your supervisor.
            
            You can view the complete task details by logging into the Task Management System.
            
            Best regards,
            Task Management System
            """,
            task.getName(),
            task.getTaskId(),
            task.getDescription(),
            task.getStatus(),
            task.getDeadline()
        );
    }

    private String createPreviousAssigneeEmail(Task task, String newAssignee) {
        return String.format("""
            Dear Team Member,
            
            A task previously assigned to you has been reassigned:
            
            Task Details:
            -------------
            Name: %s
            ID: %s
            New Assignee: %s
            Description: %s
            
            If you have any ongoing work or documentation related to this task, 
            please ensure it is properly handed over to the new assignee.
            
            If you have any questions about this reassignment, please contact your supervisor.
            
            Best regards,
            Task Management System
            """,
            task.getName(),
            task.getTaskId(),
            newAssignee,
            task.getDescription()
        );
    }
}

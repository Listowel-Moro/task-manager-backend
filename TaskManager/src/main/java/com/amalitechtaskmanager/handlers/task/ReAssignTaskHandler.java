package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.model.TaskStatus;
import com.amalitechtaskmanager.utils.TaskUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amalitechtaskmanager.model.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;

import java.time.LocalDateTime;
import java.util.Map;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.SnsUtils.sendEmailNotification;


public class ReAssignTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    //private final SnsClient snsClient = SNSClientFactory.getSnsClient();
    public static final String TABLE_NAME = System.getenv("TASKS_TABLE");
    private static final ObjectMapper objectMapper = ObjectMapperFactory.getMapper();
    private final DynamoDbClient dbClient = DynamoDbFactory.getClient();
    private final String TOPIC_ARN = "arn:aws:sns:us-east-1:123456789012:TaskNotificationTopic";

    private final String TOPIC_ARNC = System.getenv("TaskNotificationTopic");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {




        try {
            Map<String, Object> body = objectMapper.readValue(event.getBody(), Map.class);
            String taskId = event.getPathParameters().get("taskId");
            String newUserId = (String) body.get("newAssignee");
            String deadline = (String) body.get("deadline");

            if (taskId == null || newUserId == null) {
                return createResponse(400, "Missing taskId or newAssignee");
            }

            Task task = TaskUtils.getTaskById(taskId, TABLE_NAME);
            if (task == null) {
                return createResponse(404, "Task not found");
            }

            String oldUserId = task.getUserId();

            task.setUserId(newUserId);
            task.setStatus(TaskStatus.OPEN);
            if (deadline != null) {
                task.setDeadline(LocalDateTime.parse(deadline));
            }
            TaskUtils.updateTask(task, TABLE_NAME);

            String message = String.format(
                    "{ \"eventType\": \"TaskReassigned\", \"taskId\": \"%s\", \"oldAssignee\": \"%s\", \"newAssignee\": \"%s\" }",
                    taskId, oldUserId, newUserId);
            String subject = "Task Reassigned: " + task.getName();
            sendEmailNotification(TOPIC_ARNC, task.getTaskId(), subject, message);
            sendEmailNotification(TOPIC_ARNC, oldUserId, subject, message);
//            notifyUser(oldUserId, message);
//            notifyUser(newUserId, message);

            return createResponse(200, message);

        } catch (Exception e) {
            e.printStackTrace();
            return createResponse(500, "Internal Server Error: " + e.getMessage());
        }
    }


//    private void notifyUser(String userId, String message) {
//        snsClient.publish(PublishRequest.builder()
//                .topicArn(TOPIC_ARN)
//                .message(message)
//                .messageAttributes(Map.of(
//                        "user_id", MessageAttributeValue.builder()
//                                .dataType("String")
//                                .stringValue(userId)
//                                .build()
//                ))
//                .build());
//    }
}
package com.amalitechtaskmanager.handlers.task;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.model.TaskStatus;
import com.amalitechtaskmanager.utils.SchedulerUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
public class CreateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SqsClient sqsClient = SqsClient.create();
    private final SchedulerClient schedulerClient = SchedulerClient.create();
    private final SchedulerUtils schedulerUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String tasksTable = System.getenv("TASKS_TABLE");
    private final String taskAssignmentQueue = System.getenv("TASK_ASSIGNMENT_QUEUE");
    private final String taskExpirationLambdaArn = System.getenv("TASK_EXPIRATION_LAMBDA_ARN");
    private final String schedulerRoleArn = System.getenv("SCHEDULER_ROLE_ARN");

    public CreateTaskHandler() {
        this.schedulerUtils = new SchedulerUtils(schedulerClient);
    }
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            Task task = objectMapper.readValue(input.getBody(), Task.class);
            if (task.getName() == null || task.getName().isEmpty() ||
                task.getDeadline() == null  ||
                task.getUserId() == null || task.getUserId().isEmpty()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("{\"error\": \"Name, deadline, and userId are required\"}");
            }
            task.setTaskId(UUID.randomUUID().toString());
            task.setStatus(TaskStatus.OPEN);
            task.setDescription(task.getDescription() != null ? task.getDescription() : "");
            task.setCreatedAt(LocalDateTime.now());
            // Store task in DynamoDB
            context.getLogger().log("Queue URL: " + taskAssignmentQueue);

            DateTimeFormatter formatter= DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
            String createdAt=task.getCreatedAt().format(formatter);

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("taskId", AttributeValue.builder().s(task.getTaskId()).build());
            item.put("name", AttributeValue.builder().s(task.getName()).build());
            item.put("createdAt", AttributeValue.builder().s(createdAt).build());
            item.put("description", AttributeValue.builder().s(task.getDescription()).build());
            item.put("status", AttributeValue.builder().s(task.getStatus().toString()).build());
            item.put("deadline", AttributeValue.builder().s(task.getDeadline().toString()).build());
            item.put("userId", AttributeValue.builder().s(task.getUserId()).build());
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tasksTable)
                    .item(item)
                    .build());
            // Send task assignment to SQS
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(taskAssignmentQueue)
                        .messageBody(objectMapper.writeValueAsString(task))
                        .messageGroupId("task-assignments")
                        .build());
                context.getLogger().log("Message sent to the fifo queue");
            }
            catch (Exception e){
                context.getLogger().log("SQS Error: " + e.getMessage());
                throw e;
            }
            context.getLogger().log("Sending to FIFO queue with messageGroupId: task-assignments");
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(taskAssignmentQueue)
                    .messageBody(objectMapper.writeValueAsString(task))
                    .messageGroupId("task-assignments")
                    .build());

            // Schedule task expiration at deadline
            boolean scheduledExpiration = false;
            if (taskExpirationLambdaArn != null && !taskExpirationLambdaArn.isEmpty() &&
                schedulerRoleArn != null && !schedulerRoleArn.isEmpty()) {
                scheduledExpiration = schedulerUtils.scheduleTaskExpiration(task, taskExpirationLambdaArn, schedulerRoleArn);
                context.getLogger().log("Scheduled expiration for task " + task.getTaskId() + ": " + scheduledExpiration);
            } else {
                context.getLogger().log("Task expiration scheduling not configured");
            }

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("taskId", task.getTaskId());
            responseBody.put("message", "Task created and queued for assignment" +
                    (scheduledExpiration ? ", expiration scheduled" : ""));
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(objectMapper.writeValueAsString(responseBody))
                    .withHeaders(Map.of("Content-Type", "application/json"));
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            context.getLogger().log("Queue URL: " + taskAssignmentQueue);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}
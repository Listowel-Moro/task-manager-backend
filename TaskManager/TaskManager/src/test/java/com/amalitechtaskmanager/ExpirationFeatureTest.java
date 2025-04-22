package com.amalitechtaskmanager;

import com.amalitechtaskmanager.handlers.task.TaskExpirationHandler;
import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.model.TaskStatus;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple test runner for the task expiration feature.
 * This class creates a task with a past deadline, invokes the TaskExpirationHandler,
 * and checks if the task was marked as expired.
 */
public class ExpirationFeatureTest {

    private static final String TASKS_TABLE = System.getenv("TASKS_TABLE");
    
    public static void main(String[] args) {
        // Create a mock context
        Context context = new Context() {
            @Override
            public String getAwsRequestId() {
                return "test-request-id";
            }

            @Override
            public String getLogGroupName() {
                return "test-log-group";
            }

            @Override
            public String getLogStreamName() {
                return "test-log-stream";
            }

            @Override
            public String getFunctionName() {
                return "test-function";
            }

            @Override
            public String getFunctionVersion() {
                return "test-version";
            }

            @Override
            public String getInvokedFunctionArn() {
                return "test-arn";
            }

            @Override
            public LambdaLogger getLogger() {
                return new LambdaLogger() {
                    @Override
                    public void log(String message) {
                        System.out.println(message);
                    }

                    @Override
                    public void log(byte[] message) {
                        System.out.println(new String(message));
                    }
                };
            }

            @Override
            public int getRemainingTimeInMillis() {
                return 30000;
            }

            @Override
            public int getMemoryLimitInMB() {
                return 128;
            }

            @Override
            public Object getClientContext() {
                return null;
            }

            @Override
            public Object getIdentity() {
                return null;
            }
        };

        try {
            // Create DynamoDB client
            DynamoDbClient dynamoDbClient = DynamoDbClient.create();
            
            // Create a task with a past deadline
            String taskId = "test-expired-" + System.currentTimeMillis();
            createTaskWithPastDeadline(dynamoDbClient, taskId);
            
            System.out.println("Created test task with ID: " + taskId);
            
            // Wait a moment to ensure the task is created
            Thread.sleep(1000);
            
            // Check the initial status
            Task initialTask = getTask(dynamoDbClient, taskId);
            System.out.println("Initial task status: " + initialTask.getStatus());
            
            // Create and invoke the TaskExpirationHandler
            TaskExpirationHandler handler = new TaskExpirationHandler();
            ScheduledEvent event = new ScheduledEvent();
            handler.handleRequest(event, context);
            
            // Wait a moment to ensure the task is processed
            Thread.sleep(1000);
            
            // Check if the task was marked as expired
            Task updatedTask = getTask(dynamoDbClient, taskId);
            System.out.println("Updated task status: " + updatedTask.getStatus());
            
            if (updatedTask.getStatus() == TaskStatus.EXPIRED) {
                System.out.println("SUCCESS: Task was marked as expired!");
                System.out.println("Expired at: " + updatedTask.getExpiredAt());
            } else {
                System.out.println("FAILURE: Task was not marked as expired.");
            }
            
        } catch (Exception e) {
            System.err.println("Error testing task expiration: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void createTaskWithPastDeadline(DynamoDbClient dynamoDbClient, String taskId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("taskId", AttributeValue.builder().s(taskId).build());
        item.put("name", AttributeValue.builder().s("Test Expired Task").build());
        item.put("description", AttributeValue.builder().s("This task should be marked as expired").build());
        item.put("status", AttributeValue.builder().s("OPEN").build());
        
        // Set deadline to yesterday
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        item.put("deadline", AttributeValue.builder().s(yesterday.toString()).build());
        
        item.put("userId", AttributeValue.builder().s("test-user").build());
        
        PutItemRequest request = PutItemRequest.builder()
                .tableName(TASKS_TABLE)
                .item(item)
                .build();
        
        dynamoDbClient.putItem(request);
    }
    
    private static Task getTask(DynamoDbClient dynamoDbClient, String taskId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("taskId", AttributeValue.builder().s(taskId).build());
        
        GetItemRequest request = GetItemRequest.builder()
                .tableName(TASKS_TABLE)
                .key(key)
                .build();
        
        GetItemResponse response = dynamoDbClient.getItem(request);
        
        if (!response.hasItem()) {
            throw new RuntimeException("Task not found: " + taskId);
        }
        
        Map<String, AttributeValue> item = response.item();
        
        Task task = new Task();
        task.setTaskId(item.get("taskId").s());
        task.setName(item.get("name").s());
        task.setDescription(item.containsKey("description") ? item.get("description").s() : null);
        task.setStatus(TaskStatus.valueOf(item.get("status").s()));
        
        if (item.containsKey("deadline")) {
            task.setDeadline(LocalDateTime.parse(item.get("deadline").s()));
        }
        
        if (item.containsKey("completed_at")) {
            task.setCompletedAt(LocalDateTime.parse(item.get("completed_at").s()));
        }
        
        if (item.containsKey("expired_at")) {
            task.setExpiredAt(LocalDateTime.parse(item.get("expired_at").s()));
        }
        
        task.setUserId(item.containsKey("userId") ? item.get("userId").s() : null);
        
        return task;
    }
}

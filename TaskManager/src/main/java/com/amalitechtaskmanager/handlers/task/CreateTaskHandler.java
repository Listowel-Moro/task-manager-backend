package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.model.Task;
import com.amalitechtaskmanager.model.TaskStatus;
import com.amalitechtaskmanager.utils.SchedulerUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersInGroupResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicRequest;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicResponse;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.Subscription;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.isUserInAdminGroup;

public class CreateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SqsClient sqsClient = SqsClient.create();
    private final SchedulerClient schedulerClient = SchedulerClient.create();
    private final SnsClient snsClient = SnsClient.create();
    private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.create();
    private final SchedulerUtils schedulerUtils;
    private final ObjectMapper objectMapper = ObjectMapperFactory.getMapper();
    private final String tasksTable = System.getenv("TASKS_TABLE");
    private final String taskAssignmentQueue = System.getenv("TASK_ASSIGNMENT_QUEUE");
    private final String taskExpirationLambdaArn = System.getenv("TASK_EXPIRATION_LAMBDA_ARN");
    private final String schedulerRoleArn = System.getenv("SCHEDULER_ROLE_ARN");
    private final String taskExpirationUserNotificationTopicArn = System.getenv("TASK_EXPIRATION_USER_NOTIFICATION_TOPIC_ARN");
    private final String taskExpirationAdminNotificationTopicArn = System.getenv("TASK_EXPIRATION_ADMIN_NOTIFICATION_TOPIC_ARN");
    private final String userPoolId = System.getenv("USER_POOL_ID");

    public CreateTaskHandler() {
        this.schedulerUtils = new SchedulerUtils(schedulerClient);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String idToken = input.getHeaders().get("Authorization");

            if (idToken == null) {
                return createResponse(input, 401, "{\"error\": \"Unauthorized-Missing Header\"}");
            }

            if (idToken.startsWith("Bearer")) {
                idToken = idToken.substring(7);
            }

            if (!isUserInAdminGroup(idToken)) {
                return createResponse(input, 403, "{\"error\": \"Forbidden-User not authorized for this operation\"}");
            }

            Task task = objectMapper.readValue(input.getBody(), Task.class);
            if (task.getName() == null || task.getName().isEmpty() ||
                    task.getDeadline() == null ||
                    task.getUserId() == null || task.getUserId().isEmpty()) {
                return createResponse(input, 400, "{\"error\": \"Name, deadline, and userId are required\"}");
            }



            if( task.getDeadline().isBefore(task.getCreatedAt())){
                return  new APIGatewayProxyResponseEvent().withBody("{\"error\": \"task deadline cannot be before task creation date \"}")
                        .withStatusCode(433) ;
            }

            task.setTaskId(UUID.randomUUID().toString());
            task.setStatus(TaskStatus.OPEN);
            task.setDescription(task.getDescription() != null ? task.getDescription() : "");




            // Store task in DynamoDB
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
            String createdAt = task.getCreatedAt().format(formatter);



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
                context.getLogger().log("Message sent to the FIFO queue");
            } catch (Exception e) {
                context.getLogger().log("SQS Error: " + e.getMessage());
                throw e;
            }

            // Schedule task expiration at deadline
            boolean scheduledExpiration = false;
            if (taskExpirationLambdaArn != null && !taskExpirationLambdaArn.isEmpty() &&
                    schedulerRoleArn != null && !schedulerRoleArn.isEmpty()) {
                scheduledExpiration = schedulerUtils.scheduleTaskExpiration(task, taskExpirationLambdaArn, schedulerRoleArn);
                context.getLogger().log("Scheduled expiration for task " + task.getTaskId() + ": " + scheduledExpiration);
            } else {
                context.getLogger().log("Task expiration scheduling not configured");
            }

            // Subscribe user and admin emails to SNS topics
            subscribeEmailsForTask(task, context);

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("taskId", task.getTaskId());
            responseBody.put("message", "Task created and queued for assignment" +
                    (scheduledExpiration ? ", expiration scheduled" : ""));
            return createResponse(input, 200, objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            context.getLogger().log("Queue URL: " + taskAssignmentQueue);
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void subscribeEmailsForTask(Task task, Context context) {
        // Subscribe user email
        String userId = task.getUserId();
        if (userId != null && !userId.isEmpty()) {
            try {
                AdminGetUserRequest userRequest = AdminGetUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(userId)
                        .build();
                AdminGetUserResponse userResponse = cognitoClient.adminGetUser(userRequest);
                String userEmail = userResponse.userAttributes().stream()
                        .filter(attr -> "email".equals(attr.name()))
                        .findFirst()
                        .map(AttributeType::value)
                        .orElse(null);
                if (userEmail != null) {
                    subscribeEmailIfNeeded(snsClient, taskExpirationUserNotificationTopicArn, userEmail, context);
                    context.getLogger().log("Subscribed user email: " + userEmail);
                }
            } catch (Exception e) {
                context.getLogger().log("Error subscribing user email: " + e.getMessage());
            }
        }

        // Subscribe admin emails
        List<String> adminEmails = getAdminEmails(context);
        for (String adminEmail : adminEmails) {
            subscribeEmailIfNeeded(snsClient, taskExpirationAdminNotificationTopicArn, adminEmail, context);
            context.getLogger().log("Subscribed admin email: " + adminEmail);
        }
    }

    private List<String> getAdminEmails(Context context) {
        try {
            ListUsersInGroupRequest listUsersInGroupRequest = ListUsersInGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .groupName("Admins")
                    .build();
            ListUsersInGroupResponse response = cognitoClient.listUsersInGroup(listUsersInGroupRequest);
            return response.users().stream()
                    .map(user -> user.attributes().stream()
                            .filter(attr -> attr.name().equals("email"))
                            .findFirst()
                            .map(AttributeType::value)
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            context.getLogger().log("Error fetching admin emails: " + e.getMessage());
            return List.of();
        }
    }

    private boolean isEmailSubscribed(SnsClient snsClient, String topicArn, String email) {
        try {
            ListSubscriptionsByTopicRequest request = ListSubscriptionsByTopicRequest.builder()
                    .topicArn(topicArn)
                    .build();
            ListSubscriptionsByTopicResponse response = snsClient.listSubscriptionsByTopic(request);
            for (Subscription subscription : response.subscriptions()) {
                if ("email".equals(subscription.protocol()) && email.equals(subscription.endpoint())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void subscribeEmailIfNeeded(SnsClient snsClient, String topicArn, String email, Context context) {
        if (!isEmailSubscribed(snsClient, topicArn, email)) {
            try {
                SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                        .protocol("email")
                        .endpoint(email)
                        .topicArn(topicArn)
                        .returnSubscriptionArn(true)
                        .build();
                snsClient.subscribe(subscribeRequest);
                context.getLogger().log("Successfully subscribed " + email + " to topic " + topicArn);
            } catch (Exception e) {
                context.getLogger().log("Error subscribing email " + email + " to topic " + topicArn + ": " + e.getMessage());
            }
        } else {
            context.getLogger().log("Email " + email + " is already subscribed to topic " + topicArn);
        }
    }
}
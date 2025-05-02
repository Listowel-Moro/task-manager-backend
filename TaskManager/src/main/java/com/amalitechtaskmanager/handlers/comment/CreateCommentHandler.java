package com.amalitechtaskmanager.handlers.comment;

import com.amalitechtaskmanager.model.Comment;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CreateCommentHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    

    private final DynamoDbClient dynamoDbClient;
    private static final String TABLE_NAME = System.getenv("TABLE_NAME");
    private final ObjectMapper objectMapper;

    public CreateCommentHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public CreateCommentHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            Comment comment = objectMapper.readValue(input.getBody(), Comment.class);

            if (comment.getUserId() == null || comment.getTaskId() == null || comment.getContent() == null) {
                return createResponse(input, 400, "Invalid input: userId, taskId, and content are required");
            }

            String commentId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();

            comment.setCommentId(commentId);
            comment.setCreatedAt(now);
            comment.setUpdatedAt(now);

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("commentId", AttributeValue.builder().s(comment.getCommentId()).build());
            item.put("userId", AttributeValue.builder().s(comment.getUserId()).build());
            item.put("taskId", AttributeValue.builder().s(comment.getTaskId()).build());
            item.put("content", AttributeValue.builder().s(comment.getContent()).build());
            item.put("createdAt", AttributeValue.builder().s(comment.getCreatedAt().toString()).build());
            item.put("updatedAt", AttributeValue.builder().s(comment.getUpdatedAt().toString()).build());

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .build());

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("commentId", commentId);
            responseBody.put("message", "Comment created successfully");

            return createResponse(input, 200, objectMapper.writeValueAsString(responseBody));

        } catch (JsonProcessingException e) {
            return createResponse(input, 400, "Invalid JSON format in request body");
        } catch (DynamoDbException e) {
            context.getLogger().log("DynamoDB error: " + e.getMessage());
            return createResponse(input, 500, "Failed to create comment: " + e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Unexpected error: " + e.getMessage());
            return createResponse(input, 500, "Unexpected error occurred");
        }
    }

}

package com.amalitechtaskmanager.handlers.comment;


import com.amalitechtaskmanager.dto.CommentRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CreateCommentHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private static final String TABLE_NAME = "Comment";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CreateCommentHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
    }

    // For testing with dependency injection
    public CreateCommentHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            // Parse input
            CommentRequest request = objectMapper.readValue(input.getBody(), CommentRequest.class);

            if (request == null || request.userId == null || request.taskId == null ||
                    request.content == null || request.timestamp == null) {
                return createResponse(400, "Invalid input: All fields (userId, taskId, content, timestamp) are required");
            }

            // Generate unique commentId
            String commentId = UUID.randomUUID().toString();

            // Prepare item to put in DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("commentId", AttributeValue.builder().s(commentId).build());
            item.put("userId", AttributeValue.builder().s(request.userId).build());
            item.put("taskId", AttributeValue.builder().s(request.taskId).build());
            item.put("content", AttributeValue.builder().s(request.content).build());
            item.put("timestamp", AttributeValue.builder().s(request.timestamp).build());

            // Create PutItem request
            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .build();

            // Put item in DynamoDB
            dynamoDbClient.putItem(putItemRequest);

            // Prepare success response
            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("commentId", commentId);
            responseBody.put("message", "Comment created successfully");

            return createResponse(200, objectMapper.writeValueAsString(responseBody));

        } catch (JsonProcessingException e) {
            return createResponse(400, "Invalid JSON format in request body");
        } catch (DynamoDbException e) {
            context.getLogger().log("DynamoDB error: " + e.getMessage());
            return createResponse(500, "Failed to create comment: " + e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Unexpected error: " + e.getMessage());
            return createResponse(500, "Unexpected error occurred");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        response.setHeaders(headers);

        return response;
    }


}
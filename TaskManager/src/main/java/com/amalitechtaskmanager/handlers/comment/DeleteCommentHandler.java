package com.amalitechtaskmanager.handlers.comment;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.HashMap;
import java.util.Map;

public class DeleteCommentHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private static final String TABLE_NAME = "Comments";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeleteCommentHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
    }

    // For testing with dependency injection
    public DeleteCommentHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            // Parse input
            DeleteCommentRequest request = objectMapper.readValue(input.getBody(), DeleteCommentRequest.class);

            if (request == null || request.commentId == null) {
                return createResponse(400, "Invalid input: commentId is required");
            }

            // Prepare key for deletion
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("commentId", AttributeValue.builder().s(request.commentId).build());

            // Create DeleteItem request
            DeleteItemRequest deleteItemRequest = DeleteItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(key)
                    .build();

            // Delete item from DynamoDB
            dynamoDbClient.deleteItem(deleteItemRequest);

            // Prepare success response
            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("message", "Comment deleted successfully");

            return createResponse(200, objectMapper.writeValueAsString(responseBody));

        } catch (JsonProcessingException e) {
            return createResponse(400, "Invalid JSON format in request body");
        } catch (DynamoDbException e) {
            context.getLogger().log("DynamoDB error: " + e.getMessage());
            return createResponse(500, "Failed to delete comment: " + e.getMessage());
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

    // Request class to parse input
    private static class DeleteCommentRequest {
        public String commentId;
    }
}
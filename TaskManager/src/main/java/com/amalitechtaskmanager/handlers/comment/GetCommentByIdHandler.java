package com.amalitechtaskmanager.handlers.comment;

import com.amalitechtaskmanager.model.Comment;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class GetCommentByIdHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private static final String TABLE_NAME = System.getenv("TABLE_NAME");
    private final ObjectMapper objectMapper;

    public GetCommentByIdHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public GetCommentByIdHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Get commentId from path parameters
            Map<String, String> pathParameters = input.getPathParameters();
            if (pathParameters == null || !pathParameters.containsKey("commentId")) {
                return createResponse(400, "Missing commentId parameter");
            }

            String commentId = pathParameters.get("commentId");

            // Create a key to get the specific comment
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("commentId", AttributeValue.builder().s(commentId).build());

            // Execute the GetItem operation
            GetItemResponse getItemResponse = dynamoDbClient.getItem(
                    GetItemRequest.builder()
                            .tableName(TABLE_NAME)
                            .key(key)
                            .build());

            // Check if the item exists
            if (getItemResponse.item() == null || getItemResponse.item().isEmpty()) {
                return createResponse(404, "Comment not found");
            }

            // Convert DynamoDB item to Comment object
            Map<String, AttributeValue> item = getItemResponse.item();
            Comment comment = new Comment();
            comment.setCommentId(item.get("commentId").s());
            comment.setTaskId(item.get("taskId").s());
            comment.setUserId(item.get("userId").s());
            comment.setContent(item.get("content").s());
            comment.setCreatedAt(LocalDateTime.parse(item.get("createdAt").s()));
            comment.setUpdatedAt(LocalDateTime.parse(item.get("updatedAt").s()));

            // Return the comment
            return createResponse(200, objectMapper.writeValueAsString(comment));

        } catch (JsonProcessingException e) {
            return createResponse(400, "Error processing JSON: " + e.getMessage());
        } catch (DynamoDbException e) {
            context.getLogger().log("DynamoDB error: " + e.getMessage());
            return createResponse(500, "Failed to retrieve comment: " + e.getMessage());
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
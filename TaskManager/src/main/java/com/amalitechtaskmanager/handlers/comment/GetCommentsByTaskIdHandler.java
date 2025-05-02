package com.amalitechtaskmanager.handlers.comment;

import com.amalitechtaskmanager.model.Comment;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetCommentsByTaskIdHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private static final String TABLE_NAME = System.getenv("TABLE_NAME");
    private final ObjectMapper objectMapper;

    public GetCommentsByTaskIdHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public GetCommentsByTaskIdHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Get taskId from path parameters
            Map<String, String> pathParameters = input.getPathParameters();
            if (pathParameters == null || !pathParameters.containsKey("taskId")) {
                return createResponse(input, 400, "Missing taskId parameter");
            }

            String taskId = pathParameters.get("taskId");

            // Set up the query
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":taskId", AttributeValue.builder().s(taskId).build());

            // Create a query request
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(TABLE_NAME)
                    .indexName("taskId-index")  // Make sure this GSI exists on your table
                    .keyConditionExpression("taskId = :taskId")
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();

            // Execute the query
            QueryResponse queryResponse = dynamoDbClient.query(queryRequest);

            // Process the results
            List<Comment> comments = new ArrayList<>();
            for (Map<String, AttributeValue> item : queryResponse.items()) {
                Comment comment = new Comment();
                comment.setCommentId(item.get("commentId").s());
                comment.setTaskId(item.get("taskId").s());
                comment.setUserId(item.get("userId").s());
                comment.setContent(item.get("content").s());
                comment.setCreatedAt(LocalDateTime.parse(item.get("createdAt").s()));
                comment.setUpdatedAt(LocalDateTime.parse(item.get("updatedAt").s()));
                comments.add(comment);
            }

            // Return the comments
            return createResponse(input, 200, objectMapper.writeValueAsString(comments));

        } catch (JsonProcessingException e) {
            return createResponse(input, 400, "Error processing JSON: " + e.getMessage());
        } catch (DynamoDbException e) {
            context.getLogger().log("DynamoDB error: " + e.getMessage());
            return createResponse(input, 500, "Failed to retrieve comments: " + e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Unexpected error: " + e.getMessage());
            return createResponse(input, 500, "Unexpected error occurred");
        }
    }

}
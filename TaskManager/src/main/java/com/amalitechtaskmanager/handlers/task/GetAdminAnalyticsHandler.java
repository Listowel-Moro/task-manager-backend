package com.amalitechtaskmanager.handlers.task;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;
import static com.amalitechtaskmanager.utils.AnalyticsComputation.computeAnalytics;
import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class GetAdminAnalyticsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String TABLE_NAME = System.getenv("TASKS_TABLE");
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();
    private final DynamoDbClient dbClient = DynamoDbFactory.getClient();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            // Scan all tasks
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(TABLE_NAME)
                    .build();

            ScanResponse response = dbClient.scan(scanRequest);
            List<Map<String, AttributeValue>> items = response.items();

            // Convert DynamoDB items to plain maps
            List<Map<String, Object>> tasks = items.stream()
                    .map(item -> {
                        Map<String, Object> task = new HashMap<>();
                        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
                            task.put(entry.getKey(), attributeValueToSimpleValue(entry.getValue()));
                        }
                        return task;
                    })
                    .collect(Collectors.toList());

            // Calculate analytics
            Map<String, Object> analytics = computeAnalytics(tasks);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(mapper.writeValueAsString(analytics));

        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}

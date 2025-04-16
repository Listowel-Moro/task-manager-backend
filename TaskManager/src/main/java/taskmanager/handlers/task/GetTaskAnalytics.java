package taskmanager.handlers.task;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class GetTaskAnalytics implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String TABLE_NAME = "Tasks";
    private static final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbClient dbClient = DynamoDbClient.create();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            Map<String, String> queryParams = request.getQueryStringParameters();
            if (queryParams == null) queryParams = new HashMap<>();

            // Ensure user_id is provided
            String userId = queryParams.get("user_id");
            if (userId == null || userId.isEmpty()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("{\"error\": \"user_id is required\"}");
            }

            // Scan tasks for the user
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(TABLE_NAME)
                    .filterExpression("user_id = :user_id")
                    .expressionAttributeValues(Map.of(":user_id", AttributeValue.builder().s(userId).build()))
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

    private Map<String, Object> computeAnalytics(List<Map<String, Object>> tasks) {
        Map<String, Object> analytics = new HashMap<>();

        long completedTasks = 0;
        long inProgressTasks = 0;
        long deadlinePassedTasks = 0;
        Instant now = Instant.now();

        for (Map<String, Object> task : tasks) {
            String status = (String) task.getOrDefault("status", "");
            String dueDate = (String) task.get("due_date");

            // Count by status
            if ("completed".equalsIgnoreCase(status)) {
                completedTasks++;
            } else if ("in_progress".equalsIgnoreCase(status)) {
                inProgressTasks++;
            }

            // Count deadline passed (not completed and due_date < now)
            if (dueDate != null && !"completed".equalsIgnoreCase(status)) {
                try {
                    Instant due = Instant.parse(dueDate);
                    if (due.isBefore(now)) {
                        deadlinePassedTasks++;
                    }
                } catch (Exception e) {
                    // Skip invalid due_date
                }
            }
        }

        // Build analytics response
        analytics.put("totalTasks", tasks.size());
        analytics.put("completedTasks", completedTasks);
        analytics.put("inProgressTasks", inProgressTasks);
        analytics.put("deadlinePassedTasks", deadlinePassedTasks);

        return analytics;
    }

    private Object attributeValueToSimpleValue(AttributeValue value) {
        if (value.s() != null) return value.s();
        if (value.n() != null) return value.n();
        if (value.bool() != null) return value.bool();
        if (value.hasSs()) return value.ss();
        if (value.hasNs()) return value.ns();
        if (value.hasBs()) return value.bs();
        if (value.m() != null) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, AttributeValue> entry : value.m().entrySet()) {
                map.put(entry.getKey(), attributeValueToSimpleValue(entry.getValue()));
            }
            return map;
        }
        if (value.l() != null) {
            List<Object> list = new ArrayList<>();
            for (AttributeValue av : value.l()) {
                list.add(attributeValueToSimpleValue(av));
            }
            return list;
        }
        return null;
    }
}
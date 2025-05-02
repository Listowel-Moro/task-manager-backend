package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.utils.AnalyticsComputation;
import com.amalitechtaskmanager.utils.ApiResponseUtil;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.amalitechtaskmanager.constants.StringConstants.TABLE_NAME;
import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;

public class GetAdminAnalyticsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(GetAdminAnalyticsHandler.class);
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();
    private final DynamoDbClient dbClient = DynamoDbFactory.getClient();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("Processing admin analytics request");

        try {
            Map<String, String> queryParams = request.getQueryStringParameters();
            String timeRange = queryParams != null ? queryParams.get("timeRange") : "all";

            // Get tasks with potential time range filter
            List<Map<String, Object>> tasks = fetchTasksWithTimeRange(timeRange);

            // Calculate basic analytics
            Map<String, Object> analytics = AnalyticsComputation.computeAnalytics(tasks);

            String responseBody = mapper.writeValueAsString(analytics);
            logger.info("Successfully computed analytics for {} tasks", tasks.size());

            return ApiResponseUtil.createResponse(request, 200, responseBody);

        } catch (Exception e) {
            logger.error("Error processing analytics request: {}", e.getMessage(), e);
            return ApiResponseUtil.createResponse(request, 500,
                    String.format("{\"error\": \"Failed to compute analytics: %s\"}", e.getMessage()));
        }
    }

    private List<Map<String, Object>> fetchTasksWithTimeRange(String timeRange) {
        ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                .tableName(TABLE_NAME);

        // Add time range filter if specified
        if (timeRange != null && !timeRange.equals("all")) {
            Instant cutoffTime = calculateCutoffTime(timeRange);
            if (cutoffTime != null) {
                scanRequestBuilder.filterExpression("createdAt >= :cutoff")
                        .expressionAttributeValues(Map.of(
                                ":cutoff", AttributeValue.builder().s(cutoffTime.toString()).build()
                        ));
            }
        }

        ScanResponse response = dbClient.scan(scanRequestBuilder.build());
        return response.items().stream()
                .map(this::convertDynamoItemToMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> convertDynamoItemToMap(Map<String, AttributeValue> item) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            attributeValueToSimpleValue(entry.getValue())
                    .ifPresent(value -> result.put(entry.getKey(), value));
        }
        return result;


    }

    private Instant calculateCutoffTime(String timeRange) {
        Instant now = Instant.now();
        return switch (timeRange.toLowerCase()) {
            case "day" -> now.minus(1, ChronoUnit.DAYS);
            case "week" -> now.minus(7, ChronoUnit.DAYS);
            case "month" -> now.minus(30, ChronoUnit.DAYS);
            case "quarter" -> now.minus(90, ChronoUnit.DAYS);
            case "year" -> now.minus(365, ChronoUnit.DAYS);
            default -> null;
        };
    }

}
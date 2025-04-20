package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;

public class GetAdminTasksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String TABLE_NAME = "Tasks";
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();
    private final DynamoDbClient dbClient = DynamoDbFactory.getClient();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            Map<String, String> queryParams = request.getQueryStringParameters();
            if (queryParams == null) queryParams = new HashMap<>();

            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(TABLE_NAME)
                    .build();

            ScanResponse response = dbClient.scan(scanRequest);
            List<Map<String, AttributeValue>> items = response.items();

            // Convert to plain map
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, AttributeValue> item : items) {
                Map<String, Object> task = new HashMap<>();
                for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
                    task.put(entry.getKey(), attributeValueToSimpleValue(entry.getValue()));
                }
                result.add(task);
            }

            // Apply query param filtering
            Map<String, String> finalQueryParams = queryParams;
            List<Map<String, Object>> filteredResult = result.stream()
                    .filter(task -> {
                        for (Map.Entry<String, String> entry : finalQueryParams.entrySet()) {
                            Object value = task.get(entry.getKey());
                            if (value == null || !value.toString().equalsIgnoreCase(entry.getValue())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(mapper.writeValueAsString(filteredResult));

        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

}


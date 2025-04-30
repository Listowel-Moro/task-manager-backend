package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.utils.ApiResponseUtil;
import com.amalitechtaskmanager.utils.AuthorizerUtil;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.amalitechtaskmanager.constants.StringConstants.TABLE_NAME;
import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;

public class GetAdminTasksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(GetAdminTasksHandler.class);
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();
    private final DynamoDbClient dbClient = DynamoDbFactory.getClient();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        String idToken = request.getHeaders().get("Authorization");


        if (idToken == null) {
            return createResponse(request, 401, "Unauthorized-Missing Header");
        }

        if (!AuthorizerUtil.authorize(idToken)){
            return createResponse(request, 403, "not authorized to perform this operation");
        }


        logger.info("Processing request to get all admin tasks");

        try {
            Map<String, String> queryParams = request.getQueryStringParameters();
            if (queryParams == null) {
                queryParams = new HashMap<>();
            }

            // Build the scan request with proper handling of reserved keywords
            ScanRequest scanRequest = buildScanRequest(queryParams);

            logger.debug("Executing DynamoDB scan with filters: {}", queryParams);
            ScanResponse response = dbClient.scan(scanRequest);

            List<Map<String, Object>> result = response.items().stream()
                    .map(this::convertDynamoItemToMap)
                    .collect(Collectors.toList());

            String responseBody = mapper.writeValueAsString(result);
            logger.info("Successfully retrieved {} tasks", result.size());

            return ApiResponseUtil.createResponse(request, 200, responseBody);

        } catch (Exception e) {
            logger.error("Error processing admin tasks request: {}", e.getMessage(), e);
            return ApiResponseUtil.createResponse(request, 500,
                    String.format("{\"error\": \"Failed to retrieve tasks: %s\"}", e.getMessage()));
        }
    }

    private ScanRequest buildScanRequest(Map<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return ScanRequest.builder()
                    .tableName(TABLE_NAME)
                    .build();
        }

        StringBuilder filterExpression = new StringBuilder();
        Map<String, String> expressionAttributeNames = new HashMap<>();
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();

        int conditionCount = 0;

        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String attributeName = entry.getKey();
            String attributeValue = entry.getValue();

            if (conditionCount > 0) {
                filterExpression.append(" AND ");
            }

            // Handle 'status' specially as it's a reserved keyword
            if (attributeName.equals("status")) {
                String nameAlias = "#s";
                String valueAlias = ":s";

                filterExpression.append(nameAlias).append(" = ").append(valueAlias);
                expressionAttributeNames.put(nameAlias, "status");
                expressionAttributeValues.put(valueAlias, AttributeValue.builder().s(attributeValue).build());
            } else {
                // Handle other attributes
                String nameAlias = "#" + attributeName;
                String valueAlias = ":" + attributeName;

                filterExpression.append(nameAlias).append(" = ").append(valueAlias);
                expressionAttributeNames.put(nameAlias, attributeName);
                expressionAttributeValues.put(valueAlias, AttributeValue.builder().s(attributeValue).build());
            }

            conditionCount++;
        }

        ScanRequest.Builder requestBuilder = ScanRequest.builder()
                .tableName(TABLE_NAME);

        if (conditionCount > 0) {
            requestBuilder
                .filterExpression(filterExpression.toString())
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues);
        }

        return requestBuilder.build();
    }

    private Map<String, Object> convertDynamoItemToMap(Map<String, AttributeValue> item) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            attributeValueToSimpleValue(entry.getValue())
                    .ifPresent(value -> result.put(entry.getKey(), value));
        }
        return result;
    }
}
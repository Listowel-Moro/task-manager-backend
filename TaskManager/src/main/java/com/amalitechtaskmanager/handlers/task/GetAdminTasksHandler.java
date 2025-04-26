package com.amalitechtaskmanager.handlers.task;

import com.amalitechtaskmanager.factories.DynamoDbFactory;
import com.amalitechtaskmanager.factories.ObjectMapperFactory;
import com.amalitechtaskmanager.utils.ApiResponseUtil;
import com.amalitechtaskmanager.utils.AuthorizerUtil;
import com.amalitechtaskmanager.utils.DynamoFilterUtil;
import com.amalitechtaskmanager.utils.DynamoDbUtils;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.amalitechtaskmanager.constants.StringConstants.TABLE_NAME;
import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.AttributeValueConverter.attributeValueToSimpleValue;
import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.isUserInAdminGroup;

public class GetAdminTasksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(GetAdminTasksHandler.class);
    private static final ObjectMapper mapper = ObjectMapperFactory.getMapper();
    private final DynamoDbClient dbClient = DynamoDbFactory.getClient();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        String idToken = request.getHeaders().get("Authorization");


        if (idToken == null) {
            return createResponse(401, "Unauthorized-Missing Header");
        }

        if (!AuthorizerUtil.authorize(idToken)){
            return createResponse(403, "not authorized to perform this operation");
        }


        logger.info("Processing request to get all admin tasks");

        try {
            Map<String, String> queryParams = request.getQueryStringParameters();
            if (queryParams == null) {
                queryParams = new HashMap<>();
            }

            // Use DynamoFilterUtil to build the scan request with filters
            ScanRequest scanRequest = DynamoFilterUtil.buildScanRequestWithFilters(TABLE_NAME, queryParams);

            logger.debug("Executing DynamoDB scan with filters: {}", queryParams);
            ScanResponse response = dbClient.scan(scanRequest);

            // Convert DynamoDB items to plain maps using utility functions
            Map<String, String> finalQueryParams = queryParams;
            List<Map<String, Object>> result = response.items().stream()
                    .map(this::convertDynamoItemToMap)
                    .filter(task -> applyQueryFilters(task, finalQueryParams))
                    .collect(Collectors.toList());

            String responseBody = mapper.writeValueAsString(result);
            logger.info("Successfully retrieved {} tasks", result.size());

            return ApiResponseUtil.createResponse(200, responseBody);

        } catch (Exception e) {
            logger.error("Error processing admin tasks request: {}", e.getMessage(), e);
            return ApiResponseUtil.createResponse(500,
                    String.format("{\"error\": \"Failed to retrieve tasks: %s\"}", e.getMessage()));
        }
    }

    private Map<String, Object> convertDynamoItemToMap(Map<String, AttributeValue> item) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            attributeValueToSimpleValue(entry.getValue())
                    .ifPresent(value -> result.put(entry.getKey(), value));
        }
        return result;
    }

    private boolean applyQueryFilters(Map<String, Object> task, Map<String, String> queryParams) {
        return queryParams.entrySet().stream()
                .allMatch(entry -> {
                    Object taskValue = task.get(entry.getKey());
                    return taskValue != null &&
                            taskValue.toString().equalsIgnoreCase(entry.getValue());
                });
    }
}
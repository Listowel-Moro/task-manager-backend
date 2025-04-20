package com.amalitechtaskmanager.utils;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public class DynamoFilterUtil {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static ScanRequest buildScanRequestWithFilters(String tableName, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return ScanRequest.builder().tableName(tableName).build();
        }

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        StringJoiner filterExpression = new StringJoiner(" AND ");

        queryParams.forEach((key, value) -> {
            switch (key) {
                case "status":
                    filterExpression.add(key + " = :" + key);
                    expressionAttributeValues.put(":" + key, AttributeValue.builder().s(value).build());
                    break;
                case "startDate":
                    filterExpression.add("deadline >= :startDate");
                    expressionAttributeValues.put(":startDate", AttributeValue.builder().s(value).build());
                    break;
                case "endDate":
                    filterExpression.add("deadline <= :endDate");
                    expressionAttributeValues.put(":endDate", AttributeValue.builder().s(value).build());
                    break;
                case "createdStart":
                    filterExpression.add("createdAt >= :createdStart");
                    expressionAttributeValues.put(":createdStart", AttributeValue.builder().s(value).build());
                    break;
                case "createdEnd":
                    filterExpression.add("createdAt <= :createdEnd");
                    expressionAttributeValues.put(":createdEnd", AttributeValue.builder().s(value).build());
                    break;
                default:
                    break;
            }
        });

        ScanRequest.Builder requestBuilder = ScanRequest.builder()
                .tableName(tableName);

        if (!expressionAttributeValues.isEmpty()) {
            requestBuilder = requestBuilder
                    .filterExpression(filterExpression.toString())
                    .expressionAttributeValues(expressionAttributeValues);
        }

        return requestBuilder.build();
    }
}
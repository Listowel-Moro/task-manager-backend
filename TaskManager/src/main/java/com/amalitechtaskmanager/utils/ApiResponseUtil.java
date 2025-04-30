package com.amalitechtaskmanager.utils;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.util.HashMap;
import java.util.Map;

public class ApiResponseUtil {

    public static APIGatewayProxyResponseEvent createResponse(APIGatewayProxyRequestEvent input, int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "http://localhost:8080");  // For production, replace with your specific domain
        headers.put("Access-Control-Allow-Methods", "OPTIONS,POST,GET");
        headers.put("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Amz-Date,X-Api-Key,X-Amz-Security-Token");
        headers.put("Access-Control-Allow-Credentials", "true");

        response.setHeaders(headers);
        if ("OPTIONS".equalsIgnoreCase(input.getHttpMethod())) {
            response.setStatusCode(200);
            response.setBody("{}");
            return response;
        }

        response.setStatusCode(statusCode);
        response.setBody(body);

        return response;
    }
}
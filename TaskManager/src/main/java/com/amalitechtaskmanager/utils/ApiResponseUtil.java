package com.amalitechtaskmanager.utils;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.util.HashMap;
import java.util.Map;

public class ApiResponseUtil {

    public static APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "https://develop.d4p44endo1tru.amplifyapp.com");
        headers.put("Access-Control-Allow-Headers", "Content-Type,Authorization");
        headers.put("Access-Control-Allow-Methods", "OPTIONS,GET,POST,PUT,DELETE");

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(body)
                .withHeaders(headers)
                .withHeaders(Map.of("Content-Type", "application/json"));
    }
}
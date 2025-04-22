package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.regions.Region;

import java.util.HashMap;
import java.util.Map;

public class ResendVerificationCodeHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String userPoolId;
    private final String clientId;
    private final ObjectMapper objectMapper;
    private final Region region;

    public ResendVerificationCodeHandler() {
        // Get the region from environment variable or use a default
        String regionName = System.getenv("AWS_REGION");
        this.region = regionName != null ? Region.of(regionName) : Region.EU_CENTRAL_1;

        // Initialize Cognito client with explicit region
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();

        this.userPoolId = System.getenv("USER_POOL_ID");
        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        response.setHeaders(headers);

        try {
            // Parse request body to get the username
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String username = requestBody.get("username");

            if (username == null || username.trim().isEmpty()) {
                context.getLogger().log("Missing username parameter");
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Missing required parameter: username");
                response.setStatusCode(400);
                response.setBody(objectMapper.writeValueAsString(errorResponse));
                return response;
            }

            context.getLogger().log("Resending verification code for user: " + username);

            // Call Cognito to resend the verification code
            ResendConfirmationCodeRequest resendRequest = ResendConfirmationCodeRequest.builder()
                    .clientId(clientId)
                    .username(username)
                    .build();

            cognitoClient.resendConfirmationCode(resendRequest);
            context.getLogger().log("Verification code resent successfully for user: " + username);

            // Return success response
            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("status", "success");
            responseBody.put("message", "Verification code resent successfully");
            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(responseBody));

        } catch (UserNotFoundException e) {
            context.getLogger().log("User not found: " + e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "User not found");
            response.setStatusCode(404);
            try {
                response.setBody(objectMapper.writeValueAsString(errorResponse));
            } catch (Exception ex) {
                response.setBody("{\"error\": \"User not found\"}");
            }
        } catch (LimitExceededException e) {
            context.getLogger().log("Limit exceeded: " + e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Too many attempts. Please try again later.");
            response.setStatusCode(429);
            try {
                response.setBody(objectMapper.writeValueAsString(errorResponse));
            } catch (Exception ex) {
                response.setBody("{\"error\": \"Too many attempts. Please try again later.\"}");
            }
        } catch (TooManyRequestsException e) {
            context.getLogger().log("Too many requests: " + e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Rate limit exceeded. Please try again later.");
            response.setStatusCode(429);
            try {
                response.setBody(objectMapper.writeValueAsString(errorResponse));
            } catch (Exception ex) {
                response.setBody("{\"error\": \"Rate limit exceeded. Please try again later.\"}");
            }
        } catch (Exception e) {
            context.getLogger().log("Error resending verification code: " + e.getMessage());
            e.printStackTrace();

            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to resend verification code: " + e.getMessage());

            response.setStatusCode(500);
            try {
                response.setBody(objectMapper.writeValueAsString(errorResponse));
            } catch (Exception ex) {
                response.setBody("{\"error\": \"Internal server error\"}");
            }
        }

        return response;
    }
}
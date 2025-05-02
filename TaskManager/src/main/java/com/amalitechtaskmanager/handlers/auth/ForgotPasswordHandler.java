package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import com.amalitechtaskmanager.utils.ApiResponseUtil;

import java.util.HashMap;
import java.util.Map;

public class ForgotPasswordHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;
    private final ObjectMapper objectMapper;

    public ForgotPasswordHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = requestBody.get("email");

            if (email == null) {
                return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Email is required\"}");
            }

            // Create forgot password request
            ForgotPasswordRequest forgotPasswordRequest = ForgotPasswordRequest.builder()
                    .clientId(clientId)
                    .username(email)
                    .build();

            // Initiate forgot password flow
            ForgotPasswordResponse forgotPasswordResponse = cognitoClient.forgotPassword(forgotPasswordRequest);

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("message", "Password reset code sent successfully");
            responseBody.put("deliveryMedium", forgotPasswordResponse.codeDeliveryDetails().deliveryMedium());
            responseBody.put("destination", forgotPasswordResponse.codeDeliveryDetails().destination());

            return ApiResponseUtil.createResponse(input, 200, objectMapper.writeValueAsString(responseBody));

        } catch (UserNotFoundException e) {
            context.getLogger().log("User not found: " + e.getMessage());
            // For security reasons, don't reveal that the user doesn't exist
            return ApiResponseUtil.createResponse(input, 200, "{\"message\": \"If the email exists, a password reset code has been sent\"}");
        } catch (Exception e) {
            context.getLogger().log("Error in forgot password flow: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 500, "{\"message\": \"Error initiating password reset: " + e.getMessage() + "\"}");
        }
    }
}
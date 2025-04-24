// ForgotPasswordHandler.java
package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

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
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(Map.of("Content-Type", "application/json"));

        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = requestBody.get("email");

            if (email == null) {
                response.setStatusCode(400);
                response.setBody("{\"message\": \"Email is required\"}");
                return response;
            }

            // Create forgot password request
            ForgotPasswordRequest forgotPasswordRequest = ForgotPasswordRequest.builder()
                    .clientId(clientId)
                    .username(email)
                    .build();

            // Initiate forgot password flow
            ForgotPasswordResponse forgotPasswordResponse = cognitoClient.forgotPassword(forgotPasswordRequest);

            response.setStatusCode(200);
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("message", "Password reset code sent successfully");
            responseBody.put("deliveryMedium", forgotPasswordResponse.codeDeliveryDetails().deliveryMedium());
            responseBody.put("destination", forgotPasswordResponse.codeDeliveryDetails().destination());

            response.setBody(objectMapper.writeValueAsString(responseBody));

        } catch (UserNotFoundException e) {
            context.getLogger().log("User not found: " + e.getMessage());
            // For security reasons, don't reveal that the user doesn't exist
            response.setStatusCode(200);
            response.setBody("{\"message\": \"If the email exists, a password reset code has been sent\"}");
        } catch (Exception e) {
            context.getLogger().log("Error in forgot password flow: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Error initiating password reset: " + e.getMessage() + "\"}");
        }

        return response;
    }
}
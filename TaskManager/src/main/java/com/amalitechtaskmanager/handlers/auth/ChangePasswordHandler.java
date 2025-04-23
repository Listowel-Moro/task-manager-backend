// ChangePasswordHandler.java
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

public class ChangePasswordHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper;

    public ChangePasswordHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(Map.of("Content-Type", "application/json"));

        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String accessToken = requestBody.get("accessToken");
            String previousPassword = requestBody.get("previousPassword");
            String newPassword = requestBody.get("newPassword");

            if (accessToken == null || previousPassword == null || newPassword == null) {
                response.setStatusCode(400);
                response.setBody("{\"message\": \"Access token, previous password, and new password are required\"}");
                return response;
            }

            // Create change password request
            ChangePasswordRequest changePasswordRequest = ChangePasswordRequest.builder()
                    .accessToken(accessToken)
                    .previousPassword(previousPassword)
                    .proposedPassword(newPassword)
                    .build();

            // Change the password
            cognitoClient.changePassword(changePasswordRequest);

            response.setStatusCode(200);
            response.setBody("{\"message\": \"Password changed successfully\"}");

        } catch (NotAuthorizedException e) {
            context.getLogger().log("Authentication error: " + e.getMessage());
            response.setStatusCode(401);
            response.setBody("{\"message\": \"Incorrect previous password or invalid access token\"}");
        } catch (InvalidPasswordException e) {
            context.getLogger().log("Invalid password: " + e.getMessage());
            response.setStatusCode(400);
            response.setBody("{\"message\": \"Password does not meet requirements: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            context.getLogger().log("Error changing password: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Error changing password: " + e.getMessage() + "\"}");
        }

        return response;
    }
}

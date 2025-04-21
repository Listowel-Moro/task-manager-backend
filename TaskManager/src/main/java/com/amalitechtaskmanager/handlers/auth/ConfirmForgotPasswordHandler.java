// ConfirmForgotPasswordHandler.java
package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.Map;

public class ConfirmForgotPasswordHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;
    private final ObjectMapper objectMapper;

    public ConfirmForgotPasswordHandler() {
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
            String confirmationCode = requestBody.get("confirmationCode");
            String newPassword = requestBody.get("newPassword");

            if (email == null || confirmationCode == null || newPassword == null) {
                response.setStatusCode(400);
                response.setBody("{\"message\": \"Email, confirmation code, and new password are required\"}");
                return response;
            }

            // Create confirm forgot password request
            ConfirmForgotPasswordRequest confirmRequest = ConfirmForgotPasswordRequest.builder()
                    .clientId(clientId)
                    .username(email)
                    .confirmationCode(confirmationCode)
                    .password(newPassword)
                    .build();

            // Confirm password reset
            cognitoClient.confirmForgotPassword(confirmRequest);

            response.setStatusCode(200);
            response.setBody("{\"message\": \"Password reset successful\"}");

        } catch (CodeMismatchException e) {
            context.getLogger().log("Invalid confirmation code: " + e.getMessage());
            response.setStatusCode(400);
            response.setBody("{\"message\": \"Invalid confirmation code\"}");
        } catch (ExpiredCodeException e) {
            context.getLogger().log("Expired code: " + e.getMessage());
            response.setStatusCode(400);
            response.setBody("{\"message\": \"Confirmation code has expired\"}");
        } catch (InvalidPasswordException e) {
            context.getLogger().log("Invalid password: " + e.getMessage());
            response.setStatusCode(400);
            response.setBody("{\"message\": \"Password does not meet requirements: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            context.getLogger().log("Error confirming password reset: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Error resetting password: " + e.getMessage() + "\"}");
        }

        return response;
    }
}
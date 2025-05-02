package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import com.amalitechtaskmanager.utils.ApiResponseUtil;

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
        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = requestBody.get("email");
            String confirmationCode = requestBody.get("confirmationCode");
            String newPassword = requestBody.get("newPassword");

            if (email == null || confirmationCode == null || newPassword == null) {
                return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Email, confirmation code, and new password are required\"}");
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

            return ApiResponseUtil.createResponse(input, 200, "{\"message\": \"Password reset successful\"}");

        } catch (CodeMismatchException e) {
            context.getLogger().log("Invalid confirmation code: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Invalid confirmation code\"}");
        } catch (ExpiredCodeException e) {
            context.getLogger().log("Expired code: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Confirmation code has expired\"}");
        } catch (InvalidPasswordException e) {
            context.getLogger().log("Invalid password: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Password does not meet requirements: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            context.getLogger().log("Error confirming password reset: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 500, "{\"message\": \"Error resetting password: " + e.getMessage() + "\"}");
        }
    }
}
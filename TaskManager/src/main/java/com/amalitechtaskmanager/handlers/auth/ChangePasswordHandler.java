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

public class ChangePasswordHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper;

    public ChangePasswordHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String accessToken = requestBody.get("accessToken");
            String previousPassword = requestBody.get("previousPassword");
            String newPassword = requestBody.get("newPassword");

            if (accessToken == null || previousPassword == null || newPassword == null) {
                return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Access token, previous password, and new password are required\"}");
            }

            // Create change password request
            ChangePasswordRequest changePasswordRequest = ChangePasswordRequest.builder()
                    .accessToken(accessToken)
                    .previousPassword(previousPassword)
                    .proposedPassword(newPassword)
                    .build();

            // Change the password
            cognitoClient.changePassword(changePasswordRequest);

            return ApiResponseUtil.createResponse(input, 200, "{\"message\": \"Password changed successfully\"}");

        } catch (NotAuthorizedException e) {
            context.getLogger().log("Authentication error: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 401, "{\"message\": \"Incorrect previous password or invalid access token\"}");
        } catch (InvalidPasswordException e) {
            context.getLogger().log("Invalid password: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Password does not meet requirements: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            context.getLogger().log("Error changing password: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 500, "{\"message\": \"Error changing password: " + e.getMessage() + "\"}");
        }
    }
}
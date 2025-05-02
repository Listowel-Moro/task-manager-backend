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

public class SignOutHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper;

    public SignOutHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String accessToken = requestBody.get("accessToken");

            if (accessToken == null || accessToken.isEmpty()) {
                return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Access token is required\"}");
            }

            // Create global sign-out request
            GlobalSignOutRequest signOutRequest = GlobalSignOutRequest.builder()
                    .accessToken(accessToken)
                    .build();

            // Sign out user and invalidate all issued tokens
            cognitoClient.globalSignOut(signOutRequest);

            return ApiResponseUtil.createResponse(input, 200, "{\"message\": \"Successfully signed out\"}");

        } catch (NotAuthorizedException e) {
            context.getLogger().log("Authentication error during sign out: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 401, "{\"message\": \"Invalid or expired access token\"}");
        } catch (Exception e) {
            context.getLogger().log("Error during sign out: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 500, "{\"message\": \"Error signing out: " + e.getMessage() + "\"}");
        }
    }
}
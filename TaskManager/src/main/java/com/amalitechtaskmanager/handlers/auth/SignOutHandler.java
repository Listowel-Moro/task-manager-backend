// SignOutHandler.java
package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

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
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setHeaders(Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*"
        ));

        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String accessToken = requestBody.get("accessToken");

            if (accessToken == null || accessToken.isEmpty()) {
                response.setStatusCode(400);
                response.setBody("{\"message\": \"Access token is required\"}");
                return response;
            }

            // Create global sign-out request
            GlobalSignOutRequest signOutRequest = GlobalSignOutRequest.builder()
                    .accessToken(accessToken)
                    .build();

            // Sign out user and invalidate all issued tokens
            cognitoClient.globalSignOut(signOutRequest);

            response.setStatusCode(200);
            response.setBody("{\"message\": \"Successfully signed out\"}");

        } catch (NotAuthorizedException e) {
            context.getLogger().log("Authentication error during sign out: " + e.getMessage());
            response.setStatusCode(401);
            response.setBody("{\"message\": \"Invalid or expired access token\"}");
        } catch (Exception e) {
            context.getLogger().log("Error during sign out: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Error signing out: " + e.getMessage() + "\"}");
        }

        return response;
    }
}
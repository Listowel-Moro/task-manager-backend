package com.amalitechtaskmanager.handlers.auth;

import com.amalitechtaskmanager.utils.ApiResponseUtil;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.HashMap;
import java.util.Map;

public class TokenRefreshHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;
    private final ObjectMapper objectMapper;

    public TokenRefreshHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String refreshToken = requestBody.get("refreshToken");

            if (refreshToken == null || refreshToken.isEmpty()) {
                return ApiResponseUtil.createResponse(input, 400, "{\"message\": \"Refresh token is required\"}");
            }

            // Create auth parameters
            Map<String, String> authParams = new HashMap<>();
            authParams.put("REFRESH_TOKEN", refreshToken);

            // Create refresh token request
            InitiateAuthRequest refreshRequest = InitiateAuthRequest.builder()
                    .clientId(clientId)
                    .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                    .authParameters(authParams)
                    .build();

            // Request new tokens using the refresh token
            InitiateAuthResponse refreshResponse = cognitoClient.initiateAuth(refreshRequest);

            // Extract tokens from response
            Map<String, String> tokens = new HashMap<>();
            AuthenticationResultType authResult = refreshResponse.authenticationResult();

            // Note: Refresh token auth flow doesn't return a new refresh token
            tokens.put("idToken", authResult.idToken());
            tokens.put("accessToken", authResult.accessToken());
            tokens.put("expiresIn", String.valueOf(authResult.expiresIn()));
            tokens.put("tokenType", authResult.tokenType());

            return ApiResponseUtil.createResponse(input, 200, objectMapper.writeValueAsString(tokens));

        } catch (NotAuthorizedException e) {
            context.getLogger().log("Invalid refresh token: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 401, "{\"message\": \"Invalid or expired refresh token\"}");
        } catch (Exception e) {
            context.getLogger().log("Error refreshing token: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 500, "{\"message\": \"Error refreshing token: " + e.getMessage() + "\"}");
        }
    }
}
package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.regions.Region;
import com.amalitechtaskmanager.utils.ApiResponseUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ResendTempPasswordHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String userPoolId;
    private final String clientId;
    private final ObjectMapper objectMapper;
    private final Region region;

    public ResendTempPasswordHandler() {
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
        try {
            // Verify that the caller is an admin
            String authToken = input.getHeaders().get("Authorization");
            if (authToken == null || authToken.isEmpty()) {
                context.getLogger().log("Missing Authorization header");
                return ApiResponseUtil.createResponse(input, 401, "{\"error\": \"Unauthorized - Missing authentication\"}");
            }

            // Extract the token from the header (remove "Bearer " prefix if present)
            String token = authToken;
            if (authToken.startsWith("Bearer ")) {
                token = authToken.substring(7);
            }

            // Get the user from the token
            String callerUsername = getUsernameFromToken(token, context);
            if (callerUsername == null) {
                return ApiResponseUtil.createResponse(input, 401, "{\"error\": \"Invalid authentication token\"}");
            }

            // Check if the caller is an admin
            if (!isUserInAdminGroup(callerUsername, context)) {
                return ApiResponseUtil.createResponse(input, 403, "{\"error\": \"Forbidden - Admin privileges required\"}");
            }

            // Parse request body to get the target username
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String targetUsername = requestBody.get("username");

            if (targetUsername == null || targetUsername.trim().isEmpty()) {
                context.getLogger().log("Missing username parameter");
                return ApiResponseUtil.createResponse(input, 400, "{\"error\": \"Missing required parameter: username\"}");
            }

            context.getLogger().log("Admin " + callerUsername + " is resending temporary password for user: " + targetUsername);

            // Check if target user exists and get user attributes
            try {
                AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(targetUsername)
                        .build();

                AdminGetUserResponse userResponse = cognitoClient.adminGetUser(getUserRequest);

                // Generate a temporary password
                String tempPassword = generateStrongPassword();

                // Set the new temporary password for the user
                AdminSetUserPasswordRequest passwordRequest = AdminSetUserPasswordRequest.builder()
                        .userPoolId(userPoolId)
                        .username(targetUsername)
                        .password(tempPassword)
                        .permanent(false) // Set to temporary password
                        .build();

                cognitoClient.adminSetUserPassword(passwordRequest);

                // Return success response with the temporary password
                // Note: In a production environment, you should NOT return the password
                // Instead, ensure that the system sends an email with the password
                Map<String, String> responseBody = new HashMap<>();
                responseBody.put("status", "success");
                responseBody.put("message", "Temporary password has been reset");
                responseBody.put("tempPassword", tempPassword); // For development/testing only
                context.getLogger().log("Temporary password reset successfully for user: " + targetUsername);
                return ApiResponseUtil.createResponse(input, 200, objectMapper.writeValueAsString(responseBody));

            } catch (UserNotFoundException e) {
                throw e; // Re-throw to be caught by the outer catch block
            }

        } catch (UserNotFoundException e) {
            context.getLogger().log("User not found: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 404, "{\"error\": \"User not found\"}");
        } catch (NotAuthorizedException e) {
            context.getLogger().log("Not authorized: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 401, "{\"error\": \"Invalid or expired authentication\"}");
        } catch (LimitExceededException e) {
            context.getLogger().log("Limit exceeded: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 429, "{\"error\": \"Too many attempts. Please try again later.\"}");
        } catch (TooManyRequestsException e) {
            context.getLogger().log("Too many requests: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 429, "{\"error\": \"Rate limit exceeded. Please try again later.\"}");
        } catch (Exception e) {
            context.getLogger().log("Error resetting temporary password: " + e.getMessage());
            e.printStackTrace();
            return ApiResponseUtil.createResponse(input, 500, "{\"error\": \"Failed to reset temporary password: " + e.getMessage() + "\"}");
        }
    }

    // Helper method to get the username from the JWT token
    private String getUsernameFromToken(String token, Context context) {
        try {
            // For simplicity, use AdminGetUser to get user information
            // In a production app, you might want to use a JWT library to decode the token directly
            GetUserRequest getUserRequest = GetUserRequest.builder()
                    .accessToken(token)
                    .build();

            GetUserResponse userResponse = cognitoClient.getUser(getUserRequest);
            return userResponse.username();
        } catch (Exception e) {
            context.getLogger().log("Error getting user from token: " + e.getMessage());
            return null;
        }
    }

    // Helper method to check if a user is in the Admins group
    private boolean isUserInAdminGroup(String username, Context context) {
        try {
            AdminListGroupsForUserRequest listGroupsRequest = AdminListGroupsForUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminListGroupsForUserResponse response = cognitoClient.adminListGroupsForUser(listGroupsRequest);

            return response.groups().stream()
                    .anyMatch(group -> "Admins".equals(group.groupName()));
        } catch (Exception e) {
            context.getLogger().log("Error checking admin status: " + e.getMessage());
            return false;
        }
    }

    // Helper method to generate a strong random password
    private String generateStrongPassword() {
        // Generate random UUID and use it as base for password
        String basePassword = UUID.randomUUID().toString().replaceAll("-", "");

        // Ensure password meets Cognito requirements (uppercase, lowercase, number, special char)
        return "Tmp" + basePassword.substring(0, 5) + "!" + "1" + basePassword.substring(6, 10);
    }
}
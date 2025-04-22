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

public class SignInHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;
    private final String userPoolId;
    private final ObjectMapper objectMapper;

    public SignInHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
        this.userPoolId = System.getenv("USER_POOL_ID");
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
            String password = requestBody.get("password");
            String newPassword = requestBody.get("newPassword");

            if (email == null || password == null) {
                response.setStatusCode(400);
                response.setBody("{\"message\": \"Email and password are required\"}");
                return response;
            }

            // Check if email is verified
            if (!isEmailVerified(email, context)) {
                response.setStatusCode(403);
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("message", "Email not verified. Please verify your email before signing in.");
                errorResponse.put("errorType", "EMAIL_NOT_VERIFIED");
                response.setBody(objectMapper.writeValueAsString(errorResponse));
                return response;
            }

            // Create Cognito InitiateAuth request
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", email);
            authParams.put("PASSWORD", password);

            InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                    .clientId(clientId)
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            try {
                // Attempt to authenticate user
                InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);

                // Check if password change is required
                if (authResponse.challengeName() == ChallengeNameType.NEW_PASSWORD_REQUIRED) {
                    if (newPassword == null) {
                        response.setStatusCode(200);
                        Map<String, Object> responseBody = new HashMap<>();
                        responseBody.put("challengeName", "NEW_PASSWORD_REQUIRED");
                        responseBody.put("session", authResponse.session());
                        response.setBody(objectMapper.writeValueAsString(responseBody));
                        return response;
                    }

                    // If new password is provided, complete the password challenge
                    Map<String, String> challengeResponses = new HashMap<>();
                    challengeResponses.put("USERNAME", email);
                    challengeResponses.put("NEW_PASSWORD", newPassword);

                    RespondToAuthChallengeRequest challengeRequest = RespondToAuthChallengeRequest.builder()
                            .clientId(clientId)
                            .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                            .session(authResponse.session())
                            .challengeResponses(challengeResponses)
                            .build();

                    RespondToAuthChallengeResponse challengeResponse = cognitoClient.respondToAuthChallenge(challengeRequest);

                    // Return the tokens after successful password change
                    Map<String, String> tokens = new HashMap<>();
                    tokens.put("idToken", challengeResponse.authenticationResult().idToken());
                    tokens.put("accessToken", challengeResponse.authenticationResult().accessToken());
                    tokens.put("refreshToken", challengeResponse.authenticationResult().refreshToken());

                    response.setStatusCode(200);
                    response.setBody(objectMapper.writeValueAsString(tokens));
                    return response;
                }

                // Normal sign-in flow
                Map<String, String> tokens = new HashMap<>();
                tokens.put("idToken", authResponse.authenticationResult().idToken());
                tokens.put("accessToken", authResponse.authenticationResult().accessToken());
                tokens.put("refreshToken", authResponse.authenticationResult().refreshToken());

                response.setStatusCode(200);
                response.setBody(objectMapper.writeValueAsString(tokens));

            } catch (NotAuthorizedException e) {
                response.setStatusCode(401);
                response.setBody("{\"message\": \"Invalid credentials\"}");
            }

        } catch (Exception e) {
            context.getLogger().log("Error during sign in: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Error signing in: " + e.getMessage() + "\"}");
        }

        return response;
    }

    /**
     * Check if the user's email is verified in Cognito
     * @param email The user's email
     * @param context Lambda context for logging
     * @return true if email is verified, false otherwise
     */
    private boolean isEmailVerified(String email, Context context) {
        try {
            // Look up the user by email
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .build();

            AdminGetUserResponse userResponse = cognitoClient.adminGetUser(getUserRequest);

            // Check if the email_verified attribute is true
            boolean verified = userResponse.userAttributes().stream()
                    .filter(attr -> attr.name().equals("email_verified"))
                    .findFirst()
                    .map(attr -> attr.value().equalsIgnoreCase("true"))
                    .orElse(false);

            context.getLogger().log("Email verification status for " + email + ": " + verified);
            return verified;

        } catch (UserNotFoundException e) {
            context.getLogger().log("User not found: " + email);
            return false;
        } catch (Exception e) {
            context.getLogger().log("Error checking email verification status: " + e.getMessage());
            return false;
        }
    }
}
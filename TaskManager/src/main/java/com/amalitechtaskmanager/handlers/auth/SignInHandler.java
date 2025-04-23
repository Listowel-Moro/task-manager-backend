package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import software.amazon.awssdk.regions.Region;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.UUID;

public class SignInHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final SfnClient sfnClient;
    private final String clientId;
    private final String userPoolId;
    private final String teamMemberSubscriptionStepFunctionArn;
    private final ObjectMapper objectMapper;
    private static final Logger logger = Logger.getLogger(SignInHandler.class.getName());

    public SignInHandler() {
        // Get the region from environment variable
        String regionName = System.getenv("AWS_REGION");
        Region region = regionName != null ? Region.of(regionName) : Region.EU_CENTRAL_1;

        // Initialize clients with explicit region
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();

        this.sfnClient = SfnClient.builder()
                .region(region)
                .build();

        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
        this.userPoolId = System.getenv("USER_POOL_ID");
        this.teamMemberSubscriptionStepFunctionArn = System.getenv("TEAM_MEMBER_SUBSCRIPTION_STEP_FUNCTION_ARN");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        response.setHeaders(headers);

        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = requestBody.get("email");
            String password = requestBody.get("password");
            String newPassword = requestBody.get("newPassword");

            if (email == null || password == null) {
                return createErrorResponse(response, 400, "Email and password are required");
            }

            // Get user groups to determine if user is an admin
            boolean isAdmin = isUserInAdminGroup(email);
            logger.info("User " + email + " is admin: " + isAdmin);

            // For admin users, we don't need to check email verification
            // For members, check if email is verified unless they're completing NEW_PASSWORD_REQUIRED challenge
            if (!isAdmin && newPassword == null && !isEmailVerified(email)) {
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("message", "Email not verified. Please verify your email before signing in.");
                errorResponse.put("errorType", "EMAIL_NOT_VERIFIED");
                response.setStatusCode(403);
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
                        Map<String, Object> responseBody = new HashMap<>();
                        responseBody.put("challengeName", "NEW_PASSWORD_REQUIRED");
                        responseBody.put("session", authResponse.session());
                        response.setStatusCode(200);
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

                    // For member users only (not admins), auto-verify email and subscribe to step function
                    if (!isAdmin) {
                        // Auto-verify the member's email after password change
                        verifyMemberEmail(email);

                        // Subscribe the member to the step function
                        subscribeMemberToStepFunction(email, context);
                    }

                    // Return the tokens after successful password change
                    Map<String, Object> responseBody = new HashMap<>();
                    responseBody.put("idToken", challengeResponse.authenticationResult().idToken());
                    responseBody.put("accessToken", challengeResponse.authenticationResult().accessToken());
                    responseBody.put("refreshToken", challengeResponse.authenticationResult().refreshToken());
                    responseBody.put("passwordChanged", true);

                    response.setStatusCode(200);
                    response.setBody(objectMapper.writeValueAsString(responseBody));
                    return response;
                }

                // Normal sign-in flow
                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("idToken", authResponse.authenticationResult().idToken());
                responseBody.put("accessToken", authResponse.authenticationResult().accessToken());
                responseBody.put("refreshToken", authResponse.authenticationResult().refreshToken());
                responseBody.put("isAdmin", isAdmin);

                response.setStatusCode(200);
                response.setBody(objectMapper.writeValueAsString(responseBody));

            } catch (NotAuthorizedException e) {
                return createErrorResponse(response, 401, "Invalid credentials");
            }

        } catch (Exception e) {
            logger.severe("Error during sign in: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(response, 500, "Error signing in: " + e.getMessage());
        }

        return response;
    }

    /**
     * Check if the user's email is verified in Cognito
     * @param email The user's email
     * @return true if email is verified, false otherwise
     */
    private boolean isEmailVerified(String email) {
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

            logger.info("Email verification status for " + email + ": " + verified);
            return verified;

        } catch (UserNotFoundException e) {
            logger.warning("User not found: " + email);
            return false;
        } catch (Exception e) {
            logger.warning("Error checking email verification status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if the user belongs to the Admin group
     * @param email The user's email/username
     * @return true if user is in Admin group, false otherwise
     */
    private boolean isUserInAdminGroup(String email) {
        try {
            AdminListGroupsForUserRequest listGroupsRequest = AdminListGroupsForUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .build();

            AdminListGroupsForUserResponse groupsResponse = cognitoClient.adminListGroupsForUser(listGroupsRequest);

            // Log all groups the user belongs to for troubleshooting
            logger.info("User " + email + " belongs to groups: " +
                    groupsResponse.groups().stream()
                            .map(group -> group.groupName())
                            .collect(java.util.stream.Collectors.joining(", ")));

            // Check if any group is named "Admins" - note the exact spelling!
            return groupsResponse.groups().stream()
                    .anyMatch(group -> "Admins".equals(group.groupName()));

        } catch (Exception e) {
            logger.warning("Error checking user groups: " + e.getMessage());
            e.printStackTrace(); // Add stack trace for better debugging
            return false;
        }
    }

    /**
     * Mark the member's email as verified after password change
     * @param email The member's email address
     */
    private void verifyMemberEmail(String email) {
        try {
            List<AttributeType> attributes = List.of(
                    AttributeType.builder()
                            .name("email_verified")
                            .value("true")
                            .build()
            );

            AdminUpdateUserAttributesRequest updateRequest = AdminUpdateUserAttributesRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .userAttributes(attributes)
                    .build();

            cognitoClient.adminUpdateUserAttributes(updateRequest);
            logger.info("Email verified automatically for user: " + email);
        } catch (Exception e) {
            logger.warning("Failed to auto-verify email for " + email + ": " + e.getMessage());
            // Continue even if this fails
        }
    }

    /**
     * Subscribe the member to the step function workflow
     * @param email The member's email
     * @param context Lambda context for logging
     */
    private void subscribeMemberToStepFunction(String email, Context context) {
        if (teamMemberSubscriptionStepFunctionArn == null || teamMemberSubscriptionStepFunctionArn.isEmpty()) {
            logger.warning("Step function ARN is not configured. Skipping subscription.");
            return;
        }

        try {
            // Create input for the step function
            Map<String, String> stepFunctionInput = new HashMap<>();
            stepFunctionInput.put("email", email);
            stepFunctionInput.put("event", "member_password_changed");
            stepFunctionInput.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String inputJson = objectMapper.writeValueAsString(stepFunctionInput);

            // Start execution of the step function
            StartExecutionRequest startRequest = StartExecutionRequest.builder()
                    .stateMachineArn(teamMemberSubscriptionStepFunctionArn)
                    .input(inputJson)
                    .name("Member-Subscription-" + UUID.randomUUID().toString()) // Unique execution name
                    .build();

            StartExecutionResponse response = sfnClient.startExecution(startRequest);
            logger.info("Started step function execution for " + email + " with execution ARN: " + response.executionArn());

        } catch (Exception e) {
            logger.warning("Failed to start step function for " + email + ": " + e.getMessage());
            // Continue even if this fails
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(
            APIGatewayProxyResponseEvent response, int statusCode, String errorMessage) {
        try {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", errorMessage);
            response.setStatusCode(statusCode);
            response.setBody(objectMapper.writeValueAsString(errorResponse));
        } catch (Exception e) {
            response.setStatusCode(500);
            response.setBody("{\"error\": \"Internal server error\"}");
        }
        return response;
    }
}
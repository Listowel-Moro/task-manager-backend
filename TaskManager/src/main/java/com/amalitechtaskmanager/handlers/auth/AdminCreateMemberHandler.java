package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.util.*;

public class AdminCreateMemberHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final SnsClient snsClient;
    private final SfnClient sfnClient;
    private final ObjectMapper objectMapper;
    private final String userPoolId;
    private final String clientId;
    private final String taskAssignmentTopicArn;
    private final String teamMemberSubscriptionStepFunctionArn;
    private final Region region;

    public AdminCreateMemberHandler() {
        // Get the region from environment variable or use a default
        String regionName = System.getenv("AWS_REGION");
        this.region = regionName != null ? Region.of(regionName) : Region.EU_CENTRAL_1;

        // Initialize clients with explicit region
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();

        this.snsClient = SnsClient.builder()
                .region(region)
                .build();

        this.sfnClient = SfnClient.builder()
                .region(region)
                .build();

        this.userPoolId = System.getenv("USER_POOL_ID");
        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
        this.taskAssignmentTopicArn = System.getenv("TASK_ASSIGNMENT_TOPIC_ARN");
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
            // Extract user info from request body
            Map<String, Object> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = (String) requestBody.get("email");
            String name = (String) requestBody.get("name");
            String department = (String) requestBody.get("department");

            if (email == null || name == null) {
                context.getLogger().log("Missing required parameters");
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Missing required parameters: email and name");
                response.setStatusCode(400);
                response.setBody(objectMapper.writeValueAsString(errorResponse));
                return response;
            }

            context.getLogger().log("Creating member user with email: " + email);

            // Check if user already exists
            try {
                AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(email)
                        .build();
                cognitoClient.adminGetUser(getUserRequest);

                // If we get here, user exists
                context.getLogger().log("User already exists: " + email);
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "User with this email already exists");
                response.setStatusCode(409); // Conflict
                response.setBody(objectMapper.writeValueAsString(errorResponse));
                return response;
            } catch (UserNotFoundException e) {
                // User doesn't exist, proceed with creation
                context.getLogger().log("User doesn't exist, proceeding with creation");
            }

            // Create user attributes
            List<AttributeType> userAttributes = new ArrayList<>();
            userAttributes.add(AttributeType.builder().name("email").value(email).build());
            userAttributes.add(AttributeType.builder().name("email_verified").value("false").build());
            userAttributes.add(AttributeType.builder().name("name").value(name).build());

            if (department != null) {
                userAttributes.add(AttributeType.builder().name("custom:department").value(department).build());
            }

            // Create the user - this will send temporary password email
            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .userAttributes(userAttributes)
                    .desiredDeliveryMediums(DeliveryMediumType.EMAIL)
                    .messageAction(MessageActionType.SUPPRESS) // Set to SUPPRESS initially
                    .build();

            AdminCreateUserResponse createUserResponse = cognitoClient.adminCreateUser(createUserRequest);
            context.getLogger().log("User created: " + createUserResponse.user().username());

            // Add the user to the Members group
            AdminAddUserToGroupRequest addToGroupRequest = AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .groupName("member")
                    .build();
            cognitoClient.adminAddUserToGroup(addToGroupRequest);
            context.getLogger().log("User added to member group");

            // Now send a custom welcome message with the verification step
            // Create a random initial password - this won't be used by the user but is required
            String temporaryPassword = UUID.randomUUID().toString().substring(0, 12) + "A1!";

            // Set the user's password and mark it as requiring change
            AdminSetUserPasswordRequest setPasswordRequest = AdminSetUserPasswordRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .password(temporaryPassword)
                    .permanent(false) // User must change on first login
                    .build();
            cognitoClient.adminSetUserPassword(setPasswordRequest);

            // Force the email verification status to false to trigger verification flow
            AdminUpdateUserAttributesRequest updateRequest = AdminUpdateUserAttributesRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .userAttributes(
                            AttributeType.builder()
                                    .name("email_verified")
                                    .value("false")
                                    .build()
                    )
                    .build();
            cognitoClient.adminUpdateUserAttributes(updateRequest);

            // Send verification code
            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(userPoolId)
                    .clientId(clientId)
                    .authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
                    .authParameters(Map.of(
                            "USERNAME", email,
                            "PASSWORD", temporaryPassword
                    ))
                    .build();

            try {
                // Try to authenticate with the temp password
                AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);
                context.getLogger().log("Authentication successful, challenge: " + authResponse.challengeName());

                // If authentication was successful, we need to complete the auth challenge
                // and then we can resend the verification code
                if (authResponse.challengeName() == ChallengeNameType.NEW_PASSWORD_REQUIRED) {
                    // We don't actually complete the challenge since user needs to set password
                    context.getLogger().log("User needs to set a new password");
                }
            } catch (Exception e) {
                context.getLogger().log("Error authenticating with temp password: " + e.getMessage());
                // We can still proceed even if this fails
            }

            // Resend verification code
            try {
                ResendConfirmationCodeRequest resendRequest = ResendConfirmationCodeRequest.builder()
                        .clientId(clientId)
                        .username(email)
                        .build();

                ResendConfirmationCodeResponse resendResponse = cognitoClient.resendConfirmationCode(resendRequest);
                context.getLogger().log("Verification code resent: " + resendResponse.codeDeliveryDetails().attributeName());
            } catch (Exception e) {
                context.getLogger().log("Error resending verification code: " + e.getMessage());
                // Continue even if this fails
            }

            // Notify user via SNS (optional)
            if (taskAssignmentTopicArn != null && !taskAssignmentTopicArn.isEmpty()) {
                try {
                    String message = "Welcome to the Task Management System! " +
                            "Your account has been created. Please check your email for your temporary password " +
                            "and verification code.";

                    PublishRequest publishRequest = PublishRequest.builder()
                            .topicArn(taskAssignmentTopicArn)
                            .subject("Welcome to Task Management System")
                            .message(message)
                            .build();

                    PublishResponse publishResponse = snsClient.publish(publishRequest);
                    context.getLogger().log("Notification sent with message ID: " + publishResponse.messageId());
                } catch (Exception e) {
                    context.getLogger().log("Failed to send notification: " + e.getMessage());
                    // Continue even if notification fails
                }
            }

            // Return success response
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("status", "success");
            responseBody.put("message", "Member user created successfully");
            responseBody.put("user", Map.of(
                    "username", email,
                    "email", email,
                    "name", name,
                    "created", true,
                    "verification_required", true
            ));
            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Error creating member user: " + e.getMessage());
            e.printStackTrace();

            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to create member user: " + e.getMessage());

            response.setStatusCode(500);
            try {
                response.setBody(objectMapper.writeValueAsString(errorResponse));
            } catch (Exception ex) {
                response.setBody("{\"error\": \"Internal server error\"}");
            }
        }

        return response;
    }
}
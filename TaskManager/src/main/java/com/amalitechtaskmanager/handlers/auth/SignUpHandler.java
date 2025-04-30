package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;

public class SignUpHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;
    private final String userPoolId;
    private final ObjectMapper objectMapper;

    public SignUpHandler() {
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
            // Log environment variables to debug
            context.getLogger().log("ClientId: " + clientId);
            context.getLogger().log("UserPoolId: " + userPoolId);

            if (clientId == null || userPoolId == null) {
//                response.setStatusCode(500);
//                response.setBody("{\"message\": \"Environment variables not set correctly: USER_POOL_CLIENT_ID=" +
//                        clientId + ", USER_POOL_ID=" + userPoolId + "\"}");
                return createResponse(500, "{\"message\": \"Environment variables not set correctly: USER_POOL_CLIENT_ID=" +
                        clientId + ", USER_POOL_ID=" + userPoolId + "\"}");
            }

            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = requestBody.get("email");
            String password = requestBody.get("password");
            String name = requestBody.get("name");

            if (email == null || password == null) {
//                response.setStatusCode(400);
//                response.setBody("{\"message\": \"Email and password are required\"}");
                return createResponse(400, "{\"message\": \"Email and password are required\"}");
            }

            // Create user attributes
            List<AttributeType> attributes = new ArrayList<>();
            attributes.add(AttributeType.builder().name("email").value(email).build());

            if (name != null && !name.isEmpty()) {
                attributes.add(AttributeType.builder().name("name").value(name).build());
            }

            // Create Cognito SignUp request
            SignUpRequest signUpRequest = SignUpRequest.builder()
                    .clientId(clientId)
                    .username(email)
                    .password(password)
                    .userAttributes(attributes)
                    .build();

            try {
                // Sign up user
                SignUpResponse signUpResponse = cognitoClient.signUp(signUpRequest);
                context.getLogger().log("User signed up: " + signUpResponse.userSub());

                // Auto-confirm user
                AdminConfirmSignUpRequest confirmRequest = AdminConfirmSignUpRequest.builder()
                        .userPoolId(userPoolId)
                        .username(email)
                        .build();

                cognitoClient.adminConfirmSignUp(confirmRequest);
                context.getLogger().log("User confirmed successfully");

                // Add user to the "Admins" group
                AdminAddUserToGroupRequest addUserToGroupRequest = AdminAddUserToGroupRequest.builder()
                        .userPoolId(userPoolId)
                        .username(email)
                        .groupName("Admins")
                        .build();

                cognitoClient.adminAddUserToGroup(addUserToGroupRequest);
                context.getLogger().log("User added to Admins group");

                // Auto-verify email attribute
                AttributeType emailVerifiedAttribute = AttributeType.builder()
                        .name("email_verified")
                        .value("true")
                        .build();

                AdminUpdateUserAttributesRequest verifyEmailRequest = AdminUpdateUserAttributesRequest.builder()
                        .userPoolId(userPoolId)
                        .username(email)
                        .userAttributes(List.of(emailVerifiedAttribute))
                        .build();

                cognitoClient.adminUpdateUserAttributes(verifyEmailRequest);
                context.getLogger().log("User email verified automatically");

//                response.setStatusCode(200);
//                response.setBody("{\"message\": \"User signed up, confirmed, and email verified successfully\", \"userId\": \"" +
//                        signUpResponse.userSub() + "\"}");
                createResponse(200, "{\"message\": \"User signed up, confirmed, and email verified successfully\", \"userId\": \"" +
                        signUpResponse.userSub() + "\"}");
            } catch (UsernameExistsException e) {
                // If user already exists, try to confirm them anyway
                context.getLogger().log("User already exists, attempting to confirm: " + e.getMessage());

                try {
                    AdminConfirmSignUpRequest confirmRequest = AdminConfirmSignUpRequest.builder()
                            .userPoolId(userPoolId)
                            .username(email)
                            .build();

                    cognitoClient.adminConfirmSignUp(confirmRequest);

                    // Also verify email for existing users
                    AttributeType emailVerifiedAttribute = AttributeType.builder()
                            .name("email_verified")
                            .value("true")
                            .build();

                    AdminUpdateUserAttributesRequest verifyEmailRequest = AdminUpdateUserAttributesRequest.builder()
                            .userPoolId(userPoolId)
                            .username(email)
                            .userAttributes(List.of(emailVerifiedAttribute))
                            .build();

                    cognitoClient.adminUpdateUserAttributes(verifyEmailRequest);
                    context.getLogger().log("Existing user email verified automatically");

//                    response.setStatusCode(200);
//                    response.setBody("{\"message\": \"User already exists, has been confirmed, and email verified\"}");
                    createResponse(200, "{\"message\": \"User already exists, has been confirmed, and email verified\"}");
                } catch (Exception confirmException) {
                    if (confirmException.getMessage().contains("User does not exist")) {
//                        response.setStatusCode(404);
//                        response.setBody("{\"message\": \"Error: User not found\"}");
                        createResponse(404, "{\"message\": \"Error: User not found\"}");
                    } else {
//                        response.setStatusCode(400);
//                        response.setBody("{\"message\": \"User exists but could not be confirmed: " +
//                                confirmException.getMessage() + "\"}");
                        createResponse(400, "{\"message\": \"User exists but could not be confirmed: " +
                                confirmException.getMessage() + "\"}");
                    }
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing request: " + e.getMessage());
//            response.setStatusCode(500);
//            response.setBody("{\"message\": \"Error signing up: " + e.getMessage() + "\"}");
            createResponse(500, "{\"message\": \"Error signing up: " + e.getMessage() + "\"}");
        }

        return response;
    }
}
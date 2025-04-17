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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class AdminCreateMemberHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final SfnClient sfnClient;
    private final String userPoolId;
    private final String teamMemberSubscriptionStepFunctionArn;
    private final ObjectMapper objectMapper;
    private final Region region;

    public AdminCreateMemberHandler() {
        // Get the region from environment variable or use a default
        String regionName = System.getenv("AWS_REGION");
        this.region = regionName != null ? Region.of(regionName) : Region.EU_CENTRAL_1;

        // Initialize clients with explicit region
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();

        this.sfnClient = SfnClient.builder()
                .region(region)
                .build();

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
            // Extract claims and check if the user is an admin
            Map<String, Object> authorizer = input.getRequestContext().getAuthorizer();
            if (authorizer == null) {
                context.getLogger().log("No authorizer found in the request");
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Unauthorized: Authentication required");
                response.setStatusCode(401);
                response.setBody(objectMapper.writeValueAsString(errorResponse));
                return response;
            }

            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            if (claims == null) {
                context.getLogger().log("No claims found in the request");
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Unauthorized: Authentication required");
                response.setStatusCode(401);
                response.setBody(objectMapper.writeValueAsString(errorResponse));
                return response;
            }

            // Check if user belongs to Admins group
            String groups = claims.get("cognito:groups");
            if (groups == null || !groups.contains("Admins")) {
                context.getLogger().log("User is not an admin: " + claims.get("cognito:username"));
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Forbidden: Admin privileges required");
                response.setStatusCode(403);
                response.setBody(objectMapper.writeValueAsString(errorResponse));
                return response;
            }

            context.getLogger().log("Admin authorization successful: " + claims.get("cognito:username"));

            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = requestBody.get("email");
            String name = requestBody.get("name");
            String phoneNumber = requestBody.get("phoneNumber");

            context.getLogger().log("Creating user with email: " + email);

            // Generate a temporary password
            String temporaryPassword = generateRandomPassword();

            // Create user attributes
            List<AttributeType> userAttributes = new ArrayList<>();
            userAttributes.add(AttributeType.builder().name("email").value(email).build());
            userAttributes.add(AttributeType.builder().name("email_verified").value("true").build());

            if (name != null && !name.isEmpty()) {
                userAttributes.add(AttributeType.builder().name("name").value(name).build());
            }

            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                userAttributes.add(AttributeType.builder().name("phone_number").value(phoneNumber).build());
            }

            // Create user in Cognito - removed messageAction(MessageActionType.SUPPRESS)
            // to allow Cognito to automatically send welcome email with credentials
            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .temporaryPassword(temporaryPassword)
                    .userAttributes(userAttributes)
                    .build();

            AdminCreateUserResponse createUserResponse = cognitoClient.adminCreateUser(createUserRequest);
            context.getLogger().log("User created successfully in Cognito");

            // Add user to the Members group
            AdminAddUserToGroupRequest addToGroupRequest = AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .groupName("member")
                    .build();

            cognitoClient.adminAddUserToGroup(addToGroupRequest);
            context.getLogger().log("User added to member group");

            // Start the Step Function to subscribe the user to all notification topics
            try {
                // Prepare input for the Step Function
                Map<String, String> stepFunctionInput = new HashMap<>();
                stepFunctionInput.put("email", email);
                stepFunctionInput.put("user_id", email); // Using email as the user_id for filtering

                // Start execution
                StartExecutionRequest startRequest = StartExecutionRequest.builder()
                        .stateMachineArn(teamMemberSubscriptionStepFunctionArn)
                        .input(objectMapper.writeValueAsString(stepFunctionInput))
                        .build();

                context.getLogger().log("Starting step function: " + teamMemberSubscriptionStepFunctionArn);
                StartExecutionResponse startExecutionResponse = sfnClient.startExecution(startRequest);
                context.getLogger().log("Step function started with ARN: " + startExecutionResponse.executionArn());

                // Convert UserType to a Map to avoid serialization issues
                Map<String, Object> userMap = new HashMap<>();
                UserType user = createUserResponse.user();
                userMap.put("username", user.username());
                userMap.put("userStatus", user.userStatusAsString());
                userMap.put("enabled", user.enabled());
                userMap.put("userCreateDate", user.userCreateDate().toString());
                userMap.put("attributes", user.attributes().stream()
                        .collect(Collectors.toMap(
                                AttributeType::name,
                                AttributeType::value
                        )));

                // Prepare success response
                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("status", "success");
                responseBody.put("user", userMap);
                responseBody.put("message", "User created and added to member group. Welcome email sent by Cognito.");
                responseBody.put("subscriptions", "User was subscribed to all notification topics");
                responseBody.put("stepFunctionExecutionArn", startExecutionResponse.executionArn());

                response.setStatusCode(200);
                response.setBody(objectMapper.writeValueAsString(responseBody));
            } catch (Exception e) {
                context.getLogger().log("Failed to start subscription step function: " + e.getMessage());

                // Convert UserType to a Map to avoid serialization issues
                Map<String, Object> userMap = new HashMap<>();
                UserType user = createUserResponse.user();
                userMap.put("username", user.username());
                userMap.put("userStatus", user.userStatusAsString());
                userMap.put("enabled", user.enabled());
                userMap.put("userCreateDate", user.userCreateDate().toString());
                userMap.put("attributes", user.attributes().stream()
                        .collect(Collectors.toMap(
                                AttributeType::name,
                                AttributeType::value
                        )));

                // Prepare success response but with subscription warning
                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("status", "partial_success");
                responseBody.put("user", userMap);
                responseBody.put("message", "User created and added to member group. Welcome email sent by Cognito.");
                responseBody.put("subscriptionWarning", "User was created but there was an issue with topic subscriptions: " + e.getMessage());

                response.setStatusCode(200);
                response.setBody(objectMapper.writeValueAsString(responseBody));
            }

        } catch (Exception e) {
            context.getLogger().log("Error creating user: " + e.getMessage());
            e.printStackTrace();

            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to create user: " + e.getMessage());

            response.setStatusCode(500);
            try {
                response.setBody(objectMapper.writeValueAsString(errorResponse));
            } catch (Exception ex) {
                response.setBody("{\"error\": \"Internal server error\"}");
            }
        }

        return response;
    }

    private String generateRandomPassword() {
        // Generate a random password that meets Cognito requirements
        String upperChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lowerChars = "abcdefghijklmnopqrstuvwxyz";
        String numbers = "0123456789";
        String specialChars = "!@#$%^&*()_-+=<>?";

        StringBuilder password = new StringBuilder();

        // Add at least one of each required character type
        password.append(upperChars.charAt((int) (Math.random() * upperChars.length())));
        password.append(lowerChars.charAt((int) (Math.random() * lowerChars.length())));
        password.append(numbers.charAt((int) (Math.random() * numbers.length())));
        password.append(specialChars.charAt((int) (Math.random() * specialChars.length())));

        // Add additional random characters to meet minimum length
        String allChars = upperChars + lowerChars + numbers + specialChars;
        for (int i = 0; i < 8; i++) {
            password.append(allChars.charAt((int) (Math.random() * allChars.length())));
        }

        // Shuffle the password
        char[] passwordArray = password.toString().toCharArray();
        for (int i = 0; i < passwordArray.length; i++) {
            int j = (int) (Math.random() * passwordArray.length);
            char temp = passwordArray[i];
            passwordArray[i] = passwordArray[j];
            passwordArray[j] = temp;
        }

        return new String(passwordArray);
    }
}
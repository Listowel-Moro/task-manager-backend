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

import java.util.*;
import java.util.logging.Logger;
import java.util.Base64;
import com.fasterxml.jackson.core.type.TypeReference;

public class AdminCreateMemberHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final SnsClient snsClient;
    private final SfnClient sfnClient;
    private final ObjectMapper objectMapper;
    private final String userPoolId;
    private final String clientId;
    private final String taskAssignmentTopicArn;
    private final String teamMemberSubscriptionStepFunctionArn;
    private static final Logger logger = Logger.getLogger(AdminCreateMemberHandler.class.getName());

    public AdminCreateMemberHandler() {
        // Get the region from environment variable
        String regionName = System.getenv("AWS_REGION");
        Region region = regionName != null ? Region.of(regionName) : Region.EU_CENTRAL_1;

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
// hss
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        response.setHeaders(headers);

        try {
            // Get the ID token from the Authorization header
            String idToken = input.getHeaders().get("Authorization");

            // If not found, return unauthorized
            if (idToken == null) {
                return createErrorResponse(response, 401, "Unauthorized - Missing authorization header");
            }

            // Remove "Bearer " prefix if present - though it's not expected based on your format
            if (idToken.startsWith("Bearer ")) {
                idToken = idToken.substring(7);
            }

            logger.info("Checking if user is in admin group...");

            // Check if user is in admin group using the ID token
            if (!isUserInAdminGroup(idToken)) {
                return createErrorResponse(response, 403, "Forbidden - User is not authorized to create members");
            }

            logger.info("User is in admin group, proceeding with member creation");

            // Extract and validate request body
            if (input.getBody() == null || input.getBody().isEmpty()) {
                return createErrorResponse(response, 400, "Bad request - Missing request body");
            }

            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String email = requestBody.get("email");
            String name =  requestBody.get("name");
            String department = requestBody.get("department");

            if (email == null || name == null) {
                return createErrorResponse(response, 400, "Missing required parameters: email and name");
            }

            logger.info("Creating member user with email: " + email);

            // Check if user already exists
            try {
                AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(email)
                        .build();
                cognitoClient.adminGetUser(getUserRequest);

                // User exists
                return createErrorResponse(response, 409, "User with this email already exists");
            } catch (UserNotFoundException e) {
                // User doesn't exist, proceed with creation
                logger.info("User doesn't exist, proceeding with creation");
            }

            // Generate a secure temporary password
            String temporaryPassword = generateSecurePassword();

            // Create user attributes
            List<AttributeType> userAttributes = new ArrayList<>();
            userAttributes.add(AttributeType.builder().name("email").value(email).build());
            userAttributes.add(AttributeType.builder().name("email_verified").value("false").build());
            userAttributes.add(AttributeType.builder().name("name").value(name).build());

            if (department != null && !department.isEmpty()) {
                userAttributes.add(AttributeType.builder().name("custom:department").value(department).build());
            }

            // Create the user with Cognito's built-in email delivery
            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .userAttributes(userAttributes)
                    .temporaryPassword(temporaryPassword)
                    .desiredDeliveryMediums(DeliveryMediumType.EMAIL) // This will send the temp password via email
                    .build(); // Not using SUPPRESS so Cognito will send the temporary password email

            AdminCreateUserResponse createUserResponse = cognitoClient.adminCreateUser(createUserRequest);
            logger.info("User created: " + createUserResponse.user().username() + " with temporary password email sent");

            // Add the user to the Members group
            AdminAddUserToGroupRequest addToGroupRequest = AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .groupName("member")
                    .build();
            cognitoClient.adminAddUserToGroup(addToGroupRequest);
            logger.info("User added to member group");

            // Send welcome notification via SNS (optional)
            sendWelcomeNotification(email, context);

            // Return success response
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("status", "success");
            responseBody.put("message", "Member user created successfully");
            responseBody.put("user", Map.of(
                    "username", email,
                    "email", email,
                    "name", name,
                    "created", true,
                    "needs_password_change", true
            ));
            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            logger.severe("Error creating member user: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(response, 500, "Failed to create member user: " + e.getMessage());
        }

        return response;
    }

    private boolean isUserInAdminGroup(String idToken) {
        try {
            // Validate token format
            if (idToken == null || idToken.isEmpty()) {
                logger.warning("ID token is null or empty");
                return false;
            }

            // Split JWT into parts
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                logger.warning("Invalid JWT token format: Expected 3 parts, got " + parts.length);
                return false;
            }

            // Decode the payload
            String encodedPayload = parts[1];
            // Add padding if needed
            while (encodedPayload.length() % 4 != 0) {
                encodedPayload += "=";
            }

            String payload;
            try {
                payload = new String(Base64.getUrlDecoder().decode(encodedPayload));
                logger.info("Decoded JWT payload: " + payload);
            } catch (IllegalArgumentException e) {
                logger.severe("Failed to decode JWT payload with URL-safe Base64: " + e.getMessage());
                return false;
            }

            // Parse the payload as JSON
            Map<String, Object> claims = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            logger.info("Parsed token claims: " + claims);

            // Check for cognito:groups claim
            if (!claims.containsKey("cognito:groups")) {
                logger.warning("No cognito:groups claim found in the ID token");
                return false;
            }

            // Get groups as a List
            Object groupsObj = claims.get("cognito:groups");
            if (!(groupsObj instanceof List)) {
                logger.warning("cognito:groups claim is not a list: " + groupsObj);
                return false;
            }

            @SuppressWarnings("unchecked")
            List<String> groups = (List<String>) groupsObj;
            if (groups == null || groups.isEmpty()) {
                logger.warning("cognito:groups claim is empty");
                return false;
            }

            // Check if "Admins" group is present (case-sensitive)
            boolean isAdmin = groups.contains("Admins");
            logger.info("User groups: " + groups + ", isAdmin: " + isAdmin);

            return isAdmin;
        } catch (Exception e) {
            logger.severe("Error parsing ID token: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private String generateSecurePassword() {
        // Generate a secure random password that meets Cognito requirements
        String upperCaseLetters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lowerCaseLetters = "abcdefghijklmnopqrstuvwxyz";
        String numbers = "0123456789";
        String specialChars = "!@#$%^&*()_-+=<>?";
        String allowedChars = upperCaseLetters + lowerCaseLetters + numbers + specialChars;

        Random random = new Random();
        StringBuilder password = new StringBuilder();

        // Ensure we have at least one of each required character type
        password.append(upperCaseLetters.charAt(random.nextInt(upperCaseLetters.length())));
        password.append(lowerCaseLetters.charAt(random.nextInt(lowerCaseLetters.length())));
        password.append(numbers.charAt(random.nextInt(numbers.length())));
        password.append(specialChars.charAt(random.nextInt(specialChars.length())));

        // Add remaining characters to meet minimum length of 8
        for (int i = 0; i < 8; i++) {
            password.append(allowedChars.charAt(random.nextInt(allowedChars.length())));
        }

        // Shuffle the password characters
        char[] passwordArray = password.toString().toCharArray();
        for (int i = 0; i < passwordArray.length; i++) {
            int j = random.nextInt(passwordArray.length);
            char temp = passwordArray[i];
            passwordArray[i] = passwordArray[j];
            passwordArray[j] = temp;
        }

        return new String(passwordArray);
    }

    private void sendWelcomeNotification(String email, Context context) {
        if (taskAssignmentTopicArn != null && !taskAssignmentTopicArn.isEmpty()) {
            try {
                String message = "Welcome to the Task Management System! " +
                        "Your account has been created. Please check your email for your temporary password. " +
                        "You will be required to change your password on first login.";

                PublishRequest publishRequest = PublishRequest.builder()
                        .topicArn(taskAssignmentTopicArn)
                        .subject("Welcome to Task Management System")
                        .message(message)
                        .messageAttributes(Map.of(
                                "email", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue(email)
                                        .build()
                        ))
                        .build();

                PublishResponse publishResponse = snsClient.publish(publishRequest);
                logger.info("Welcome notification sent with message ID: " + publishResponse.messageId());
            } catch (Exception e) {
                logger.warning("Failed to send welcome notification: " + e.getMessage());
                // Continue even if notification fails
            }
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
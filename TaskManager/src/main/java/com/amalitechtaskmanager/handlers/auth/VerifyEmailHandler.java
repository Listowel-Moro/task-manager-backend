package com.amalitechtaskmanager.handlers.auth;

import com.amalitechtaskmanager.utils.ApiResponseUtil;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.util.HashMap;
import java.util.Map;

public class VerifyEmailHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final SfnClient sfnClient;
    private final String userPoolId;
    private final String clientId;
    private final String teamMemberSubscriptionStepFunctionArn;
    private final ObjectMapper objectMapper;
    private final Region region;

    public VerifyEmailHandler() {
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
        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
        this.teamMemberSubscriptionStepFunctionArn = System.getenv("TEAM_MEMBER_SUBSCRIPTION_STEP_FUNCTION_ARN");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse request body
            Map<String, String> requestBody = objectMapper.readValue(input.getBody(), Map.class);
            String username = requestBody.get("username");
            String code = requestBody.get("code");

            if (username == null || code == null) {
                context.getLogger().log("Missing required parameters");
                return ApiResponseUtil.createResponse(input, 400, "{\"error\": \"Missing required parameters: username and code\"}");
            }

            context.getLogger().log("Verifying email for user: " + username);

            // Verify the user's email with the confirmation code
            ConfirmSignUpRequest confirmSignUpRequest = ConfirmSignUpRequest.builder()
                    .clientId(clientId)
                    .username(username)
                    .confirmationCode(code)
                    .build();

            cognitoClient.confirmSignUp(confirmSignUpRequest);
            context.getLogger().log("Email verification successful for user: " + username);

            // Start the Step Function to subscribe the user to all notification topics
            try {
                // Prepare input for the Step Function
                Map<String, String> stepFunctionInput = new HashMap<>();
                stepFunctionInput.put("email", username);
                stepFunctionInput.put("user_id", username); // Using email as the user_id for filtering

                // Start execution
                StartExecutionRequest startRequest = StartExecutionRequest.builder()
                        .stateMachineArn(teamMemberSubscriptionStepFunctionArn)
                        .input(objectMapper.writeValueAsString(stepFunctionInput))
                        .build();

                context.getLogger().log("Starting step function for topic subscriptions: " + teamMemberSubscriptionStepFunctionArn);
                StartExecutionResponse startExecutionResponse = sfnClient.startExecution(startRequest);
                context.getLogger().log("Step function started with ARN: " + startExecutionResponse.executionArn());

                // Return success response with subscription information
                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("status", "success");
                responseBody.put("message", "Email verification successful");
                responseBody.put("subscriptions", "User was subscribed to all notification topics");
                responseBody.put("stepFunctionExecutionArn", startExecutionResponse.executionArn());
                return ApiResponseUtil.createResponse(input, 200, objectMapper.writeValueAsString(responseBody));

            } catch (Exception e) {
                context.getLogger().log("Failed to start subscription step function: " + e.getMessage());

                // Return success response for email verification but with subscription warning
                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("status", "partial_success");
                responseBody.put("message", "Email verification successful");
                responseBody.put("subscriptionWarning", "There was an issue with topic subscriptions: " + e.getMessage());
                return ApiResponseUtil.createResponse(input, 200, objectMapper.writeValueAsString(responseBody));
            }

        } catch (CodeMismatchException e) {
            context.getLogger().log("Invalid verification code: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 400, "{\"error\": \"Invalid verification code\"}");
        } catch (ExpiredCodeException e) {
            context.getLogger().log("Verification code expired: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 400, "{\"error\": \"Verification code expired\"}");
        } catch (UserNotFoundException e) {
            context.getLogger().log("User not found: " + e.getMessage());
            return ApiResponseUtil.createResponse(input, 404, "{\"error\": \"User not found\"}");
        } catch (Exception e) {
            context.getLogger().log("Error verifying email: " + e.getMessage());
            e.printStackTrace();
            return ApiResponseUtil.createResponse(input, 500, "{\"error\": \"Failed to verify email: " + e.getMessage() + "\"}");
        }
    }
}
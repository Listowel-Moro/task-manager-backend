package taskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;

import java.util.HashMap;
import java.util.Map;

public class SignInHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final String clientId;
    private final ObjectMapper objectMapper;

    public SignInHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.create();
        this.clientId = System.getenv("USER_POOL_CLIENT_ID");
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

            if (email == null || password == null) {
                response.setStatusCode(400);
                response.setBody("{\"message\": \"Email and password are required\"}");
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

            // Authenticate user
            InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);

            // Prepare response with tokens
            Map<String, String> tokens = new HashMap<>();
            tokens.put("idToken", authResponse.authenticationResult().idToken());
            tokens.put("accessToken", authResponse.authenticationResult().accessToken());
            tokens.put("refreshToken", authResponse.authenticationResult().refreshToken());

            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(tokens));

        } catch (Exception e) {
            response.setStatusCode(500);
            response.setBody("{\"message\": \"Error signing in: " + e.getMessage() + "\"}");
        }

        return response;
    }
}
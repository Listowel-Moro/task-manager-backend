package com.amalitechtaskmanager.handlers.member;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.regions.Region;
import com.amalitechtaskmanager.utils.ApiResponseUtil;

import java.util.*;
import java.util.logging.Logger;

public class GetAllMembersHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper;
    private final String userPoolId;
    private static final Logger logger = Logger.getLogger(GetAllMembersHandler.class.getName());

    public GetAllMembersHandler() {
        String regionName = System.getenv("AWS_REGION");
        Region region = regionName != null ? Region.of(regionName) : Region.EU_CENTRAL_1;

        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();

        this.userPoolId = System.getenv("USER_POOL_ID");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String authHeader = input.getHeaders().get("Authorization");
            if (authHeader == null) {
                return ApiResponseUtil.createResponse(401, "{\"error\": \"Unauthorized - Missing authorization header\"}");
            }

            // Extract token, handling both "Bearer" prefix and raw token cases
            String idToken = authHeader;
            if (authHeader.toLowerCase().startsWith("bearer")) {
                idToken = authHeader.substring(authHeader.indexOf(" ") + 1).trim();
            }

            // Check if user is in admin group
            if (!isUserInAdminGroup(idToken)) {
                return ApiResponseUtil.createResponse(403, "{\"error\": \"Forbidden - User is not authorized to view members\"}");
            }

            // List users in the member group
            ListUsersInGroupRequest listUsersRequest = ListUsersInGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .groupName("member")
                    .build();

            ListUsersInGroupResponse listUsersResponse = cognitoClient.listUsersInGroup(listUsersRequest);

            // Transform user data
            List<Map<String, Object>> members = new ArrayList<>();
            for (UserType user : listUsersResponse.users()) {
                Map<String, Object> memberInfo = new HashMap<>();
                memberInfo.put("username", user.username());
                memberInfo.put("enabled", user.enabled());
                memberInfo.put("userStatus", user.userStatusAsString());
                memberInfo.put("userCreateDate", user.userCreateDate().toString());

                // Extract attributes (email, name, etc.)
                for (AttributeType attribute : user.attributes()) {
                    switch (attribute.name()) {
                        case "email":
                            memberInfo.put("email", attribute.value());
                            break;
                        case "name":
                            memberInfo.put("name", attribute.value());
                            break;
                        case "custom:department":
                            memberInfo.put("department", attribute.value());
                            break;
                    }
                }
                members.add(memberInfo);
            }

            // Create success response
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("members", members);
            responseBody.put("count", members.size());

            return ApiResponseUtil.createResponse(200, objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            logger.severe("Error fetching members: " + e.getMessage());
            return ApiResponseUtil.createResponse(500, "{\"error\": \"Failed to fetch members: " + e.getMessage() + "\"}");
        }
    }

    private boolean isUserInAdminGroup(String idToken) {
        try {
            if (idToken == null || idToken.isEmpty()) {
                return false;
            }

            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                return false;
            }

            String encodedPayload = parts[1];
            while (encodedPayload.length() % 4 != 0) {
                encodedPayload += "=";
            }

            String payload = new String(Base64.getUrlDecoder().decode(encodedPayload));
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);

            if (!claims.containsKey("cognito:groups")) {
                return false;
            }

            List<String> groups = (List<String>) claims.get("cognito:groups");
            return groups != null && groups.contains("Admins");

        } catch (Exception e) {
            logger.severe("Error parsing ID token: " + e.getMessage());
            return false;
        }
    }
}
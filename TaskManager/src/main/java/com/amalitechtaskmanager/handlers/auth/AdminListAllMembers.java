package com.amalitechtaskmanager.handlers.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import javax.print.DocFlavor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.isUserInAdminGroup;
import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;

public class AdminListAllMembers implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private  final  CognitoIdentityProviderClient cognitoClient;
    private final  ObjectMapper objectMapper;
    private final  String userPoolId;

    public AdminListAllMembers(CognitoIdentityProviderClient cognitoClient, ObjectMapper objectMapper, String userPoolId) {
        String regionName = System.getenv("AWS_REGION");
        Region region = regionName != null ? Region.of(regionName) : Region.EU_CENTRAL_1;
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();
        this.objectMapper = objectMapper;
        this.userPoolId = userPoolId;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent requestEvent, Context context) {


        try {
            String idToken = requestEvent.getHeaders().get("Authorization");

            if (idToken == null) {
                return createResponse(requestEvent, 401, "Unauthorized-Missing Header");
            }

            if (idToken.startsWith("Bearer")) {
                idToken = idToken.substring(7);
            }


            if (!isUserInAdminGroup(idToken)) {
                return createResponse(requestEvent, 403, "Forbidden-User not authorized for this operation");
            }

                ListUsersInGroupRequest listUsersInGroupRequest = ListUsersInGroupRequest.builder()
                        .userPoolId(userPoolId)
                        .groupName("user")
                        .build();
                ListUsersInGroupResponse response = cognitoClient.listUsersInGroup(listUsersInGroupRequest);
                var result = objectMapper.writeValueAsString(response);
                return new APIGatewayProxyResponseEvent().withStatusCode(200)
                        .withBody(result);



        } catch (Exception e) {
            Logger.getAnonymousLogger().info(e.getMessage());


            return createResponse(requestEvent, 500, "Internal ServerError:" + e.getMessage());
        }


    }
}

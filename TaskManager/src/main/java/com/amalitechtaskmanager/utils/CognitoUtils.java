package com.amalitechtaskmanager.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;

import java.util.Optional;

public class CognitoUtils {

    private static final Logger logger = LoggerFactory.getLogger(CognitoUtils.class);

    public static Optional<String> getUserEmail(CognitoIdentityProviderClient client, String userPoolId, String userEmail) {
        try {
            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(userEmail)
                    .build();

            AdminGetUserResponse response = client.adminGetUser(request);
            return response.userAttributes().stream()
                    .filter(attr -> "email".equals(attr.name()))
                    .map(AttributeType::value)
                    .findFirst();

        } catch (Exception e) {
            logger.error("Failed to fetch user {}: {}", userEmail, e.getMessage());
            return Optional.empty();
        }
    }
}

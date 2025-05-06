package com.amalitechtaskmanager.utils;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class CheckUserRoleUtil {
  static  Logger logger= Logger.getAnonymousLogger();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    public static  boolean isUserInAdminGroup(String idToken) {
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

    public static String getCurrentUserEmail(APIGatewayProxyRequestEvent event) {
        try {
            if (event == null || event.getRequestContext() == null ||
                    event.getRequestContext().getAuthorizer() == null) {
                logger.warning("Request context or authorizer is null");
                return "";
            }

            Map<String, Object> authorizer = event.getRequestContext().getAuthorizer();

            @SuppressWarnings("unchecked")
            Map<String, Object> claims = (Map<String, Object>) authorizer.get("claims");

            if (claims == null) {
                logger.warning("No claims found in authorizer");
                return "";
            }

            return (String) claims.get("email");
        } catch (Exception e) {
            logger.severe("Error extracting user email: " + e.getMessage());
            return "";
        }
    }
}

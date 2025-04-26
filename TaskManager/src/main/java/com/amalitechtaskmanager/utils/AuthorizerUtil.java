package com.amalitechtaskmanager.utils;

import static com.amalitechtaskmanager.utils.ApiResponseUtil.createResponse;
import static com.amalitechtaskmanager.utils.CheckUserRoleUtil.isUserInAdminGroup;

public class AuthorizerUtil {

    public static boolean authorize(String idToken) {


        if (idToken.startsWith("Bearer")) {
            idToken = idToken.substring(7);
        }

        isUserInAdminGroup(idToken);

        return true;
    }
}

package com.amalitechtaskmanager.utils;

public class NotificationResponse {
    private final boolean success;
    private final String message;

    public NotificationResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

}

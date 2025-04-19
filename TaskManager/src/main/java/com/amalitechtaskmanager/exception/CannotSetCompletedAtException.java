package com.amalitechtaskmanager.exception;

public class CannotSetCompletedAtException extends RuntimeException {
    public CannotSetCompletedAtException(String message) {
        super(message);
    }
}

package com.amalitechtaskmanager.exception;

public class FailedToDeleteException extends RuntimeException {
    public FailedToDeleteException(String message) {
        super(message);
    }
}

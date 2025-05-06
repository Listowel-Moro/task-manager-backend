package com.amalitechtaskmanager.exception;

public class DeadlineMustbeAfterCreatedTimeException extends RuntimeException {
    public DeadlineMustbeAfterCreatedTimeException(String message) {
        super(message);
    }
}

// src/main/java/com/financecoach/backend/exception/UnauthorizedAccessException.java
package com.financecoach.backend.exception;

public class UnauthorizedAccessException extends FinanceCoachException {

    public UnauthorizedAccessException(String resource) {
        super("Unauthorized access to: " + resource, "UNAUTHORIZED");
    }

    public UnauthorizedAccessException(String resource, String reason) {
        super("Unauthorized access to " + resource + ": " + reason, "UNAUTHORIZED");
    }
}
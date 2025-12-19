// src/main/java/com/financecoach/backend/exception/FinanceCoachException.java
package com.financecoach.backend.exception;

/**
 * Base exception for all application-specific exceptions
 */
public abstract class FinanceCoachException extends RuntimeException {

    private final String errorCode;

    public FinanceCoachException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public FinanceCoachException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
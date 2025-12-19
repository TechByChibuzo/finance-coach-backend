// src/main/java/com/financecoach/backend/exception/ValidationException.java
package com.financecoach.backend.exception;

import java.util.Map;

public class ValidationException extends FinanceCoachException {

    private final Map<String, String> fieldErrors;

    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(message, "VALIDATION_ERROR");
        this.fieldErrors = fieldErrors;
    }

    public ValidationException(String message) {
        super(message, "VALIDATION_ERROR");
        this.fieldErrors = Map.of();
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
// src/main/java/com/financecoach/backend/exception/InvalidCredentialsException.java
package com.financecoach.backend.exception;

public class InvalidCredentialsException extends FinanceCoachException {

    public InvalidCredentialsException() {
        super("Invalid email or password", "INVALID_CREDENTIALS");
    }
}
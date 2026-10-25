package com.financecoach.backend.exception;

public class InvalidCredentialsException extends FinanceCoachException {

    public InvalidCredentialsException() {
        super("Invalid email or password", "INVALID_CREDENTIALS");
    }
}
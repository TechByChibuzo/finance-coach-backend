package com.financecoach.backend.exception;

public class EmailAlreadyExistsException extends FinanceCoachException {

    public EmailAlreadyExistsException(String email) {
        super("Email already registered: " + email, "EMAIL_EXISTS");
    }
}
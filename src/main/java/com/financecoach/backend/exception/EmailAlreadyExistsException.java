// src/main/java/com/financecoach/backend/exception/EmailAlreadyExistsException.java
package com.financecoach.backend.exception;

public class EmailAlreadyExistsException extends FinanceCoachException {

    public EmailAlreadyExistsException(String email) {
        super("Email already registered: " + email, "EMAIL_EXISTS");
    }
}
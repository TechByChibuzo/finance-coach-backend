// src/main/java/com/financecoach/backend/exception/UserNotFoundException.java
package com.financecoach.backend.exception;

import java.util.UUID;

public class UserNotFoundException extends FinanceCoachException {

    public UserNotFoundException(UUID userId) {
        super("User not found with id: " + userId, "USER_NOT_FOUND");
    }

    public UserNotFoundException(String email) {
        super("User not found with email: " + email, "USER_NOT_FOUND");
    }
}
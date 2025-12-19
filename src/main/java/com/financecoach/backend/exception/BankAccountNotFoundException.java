// src/main/java/com/financecoach/backend/exception/BankAccountNotFoundException.java
package com.financecoach.backend.exception;

import java.util.UUID;

public class BankAccountNotFoundException extends FinanceCoachException {

    public BankAccountNotFoundException(UUID accountId) {
        super("Bank account not found with id: " + accountId, "ACCOUNT_NOT_FOUND");
    }
}
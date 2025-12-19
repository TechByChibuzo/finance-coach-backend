// src/main/java/com/financecoach/backend/exception/BudgetNotFoundException.java
package com.financecoach.backend.exception;

import java.util.UUID;

public class BudgetNotFoundException extends FinanceCoachException {

    public BudgetNotFoundException(UUID budgetId) {
        super("Budget not found with id: " + budgetId, "BUDGET_NOT_FOUND");
    }
}
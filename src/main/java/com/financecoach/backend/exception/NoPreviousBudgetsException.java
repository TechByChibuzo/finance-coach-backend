package com.financecoach.backend.exception;

public class NoPreviousBudgetsException extends FinanceCoachException {

    public NoPreviousBudgetsException() {
        super("No budgets found for previous month", "NO_PREVIOUS_BUDGETS");
    }
}
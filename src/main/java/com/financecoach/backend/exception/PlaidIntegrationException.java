package com.financecoach.backend.exception;

public class PlaidIntegrationException extends FinanceCoachException {

    public PlaidIntegrationException(String message) {
        super("Plaid API error: " + message, "PLAID_ERROR");
    }

    public PlaidIntegrationException(String message, Throwable cause) {
        super("Plaid API error: " + message, "PLAID_ERROR", cause);
    }
}
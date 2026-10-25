package com.financecoach.backend.exception;

/**
 * Stripe couldn't be reached or refused a request, so nothing was changed. Answered with 503 and
 * a message the user can act on ("try again"); the Stripe error itself is only logged.
 */
public class BillingUnavailableException extends FinanceCoachException {

    public BillingUnavailableException(String message, Throwable cause) {
        super(message, "BILLING_UNAVAILABLE", cause);
    }
}

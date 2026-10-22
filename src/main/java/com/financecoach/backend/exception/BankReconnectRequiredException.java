package com.financecoach.backend.exception;

/**
 * The bank wants the user to sign in again (password changed, new security step, consent
 * expired) before Plaid can fetch anything more for this connection
 */
public class BankReconnectRequiredException extends PlaidIntegrationException {

    public BankReconnectRequiredException(String message) {
        super(message);
    }
}

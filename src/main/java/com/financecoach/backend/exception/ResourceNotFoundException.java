package com.financecoach.backend.exception;

/**
 * Something the request refers to doesn't exist (an asset, a debt, a plan). Answered with 404.
 */
public class ResourceNotFoundException extends FinanceCoachException {

    public ResourceNotFoundException(String resource) {
        super(resource + " not found", "NOT_FOUND");
    }
}

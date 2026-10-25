package com.financecoach.backend.exception;

public class AiServiceUnavailableException extends FinanceCoachException {

    public AiServiceUnavailableException(String message) {
        super(message, "AI_UNAVAILABLE");
    }

    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, "AI_UNAVAILABLE", cause);
    }
}

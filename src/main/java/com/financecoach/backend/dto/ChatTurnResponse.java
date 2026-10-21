package com.financecoach.backend.dto;

import java.time.LocalDateTime;

/**
 * One saved exchange with the AI coach: what the user asked and how it replied
 */
public record ChatTurnResponse(String userMessage, String assistantMessage, LocalDateTime createdAt) {
}

package com.financecoach.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * New category for a transaction. With remember, the same category is used for every past and
 * future transaction from that merchant.
 */
public record CategoryUpdateRequest(@NotBlank String category, boolean remember) {
}

package com.financecoach.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The parts to split a transaction into; their amounts must add up to the transaction's amount
 */
public record TransactionSplitRequest(@NotNull @Size(min = 2, max = 10) List<@Valid Part> parts) {

    public record Part(@NotBlank String category, @NotNull BigDecimal amount) {
    }
}

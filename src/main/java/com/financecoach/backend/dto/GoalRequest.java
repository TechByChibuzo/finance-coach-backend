package com.financecoach.backend.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A goal to create or replace. With accountId, progress follows that account's balance;
 * without it, savedAmount is the progress so far.
 */
public record GoalRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal targetAmount,
        LocalDate targetDate,
        UUID accountId,
        @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal savedAmount) {
}

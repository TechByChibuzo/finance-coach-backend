package com.financecoach.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A goal with its progress.
 *
 * @param saved         the linked account's balance, or the amount tracked by hand
 * @param percent       0-100
 * @param monthlyNeeded what to save each month from now on to reach the target by its date;
 *                      null without a date, once reached, or after the date
 */
public record SavingsGoalResponse(UUID id, String name, BigDecimal targetAmount, LocalDate targetDate,
                                  UUID accountId, String accountName, BigDecimal saved, int percent,
                                  BigDecimal remaining, BigDecimal monthlyNeeded, boolean reached,
                                  boolean pastDue) {
}

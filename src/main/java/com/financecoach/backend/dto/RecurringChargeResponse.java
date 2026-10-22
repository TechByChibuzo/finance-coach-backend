package com.financecoach.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A charge that repeats on a schedule, found in the user's transaction history.
 *
 * @param type          "subscription" when the amount is the same every time, "bill" when it varies
 * @param frequency     "monthly", "quarterly" or "yearly"
 * @param previousAmount the steady amount before the latest charge, when the latest one differs (a price change)
 */
public record RecurringChargeResponse(
        String merchant,
        String logoUrl,
        String category,
        String type,
        String frequency,
        BigDecimal amount,
        BigDecimal previousAmount,
        LocalDate lastDate,
        LocalDate nextDate,
        int occurrences
) {
}

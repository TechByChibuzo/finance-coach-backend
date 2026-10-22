package com.financecoach.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Last week's money in brief, for the Monday email.
 *
 * @param topCategories up to three categories by spending last week, largest first
 * @param upcoming      bills and subscriptions expected in the coming week
 */
public record WeeklySummary(LocalDate weekStart, LocalDate weekEnd, BigDecimal spent, BigDecimal previousWeekSpent,
                            List<CategoryTotal> topCategories, List<RecurringChargeResponse> upcoming) {

    public record CategoryTotal(String category, BigDecimal amount) {
    }
}

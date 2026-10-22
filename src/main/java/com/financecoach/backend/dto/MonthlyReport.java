package com.financecoach.backend.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One month of money in and out. Transfers between the user's own accounts, hidden transactions
 * and split originals (their parts count instead) are left out, as everywhere else.
 *
 * @param month              "2026-10"
 * @param net                income minus spending; what was saved, or overspent when negative
 * @param spendingByCategory spending per category code, largest first
 */
public record MonthlyReport(String month, BigDecimal income, BigDecimal spending, BigDecimal net,
                            Map<String, BigDecimal> spendingByCategory) {
}

// src/main/java/com/financecoach/backend/dto/BudgetSummaryResponse.java
package com.financecoach.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class BudgetSummaryResponse {
    private LocalDate month;
    private BigDecimal totalBudget;
    private BigDecimal totalSpent;
    private BigDecimal totalRemaining;
    private Double percentageSpent;
    private Integer categoriesCount;
    private Integer exceededCount;
    private Integer alertCount;
    private List<BudgetResponse> budgets;
    private String status;  // "on_track", "warning", "exceeded"

    // Constructors
    public BudgetSummaryResponse() {}

    public BudgetSummaryResponse(LocalDate month, BigDecimal totalBudget, BigDecimal totalSpent,
                                 BigDecimal totalRemaining, Double percentageSpent,
                                 Integer categoriesCount, Integer exceededCount,
                                 Integer alertCount, List<BudgetResponse> budgets,
                                 String status) {
        this.month = month;
        this.totalBudget = totalBudget;
        this.totalSpent = totalSpent;
        this.totalRemaining = totalRemaining;
        this.percentageSpent = percentageSpent;
        this.categoriesCount = categoriesCount;
        this.exceededCount = exceededCount;
        this.alertCount = alertCount;
        this.budgets = budgets;
        this.status = status;
    }


}
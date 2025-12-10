// src/main/java/com/financecoach/userservice/dto/BudgetSummaryResponse.java
package com.financecoach.userservice.dto;

import java.time.LocalDate;
import java.util.List;

public class BudgetSummaryResponse {
    private LocalDate month;
    private Double totalBudget;
    private Double totalSpent;
    private Double totalRemaining;
    private Double percentageSpent;
    private Integer categoriesCount;
    private Integer exceededCount;
    private Integer alertCount;
    private List<BudgetResponse> budgets;
    private String status;  // "on_track", "warning", "exceeded"

    // Constructors
    public BudgetSummaryResponse() {}

    public BudgetSummaryResponse(LocalDate month, Double totalBudget, Double totalSpent,
                                 Double totalRemaining, Double percentageSpent,
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

    // Getters and Setters
    public LocalDate getMonth() {
        return month;
    }

    public void setMonth(LocalDate month) {
        this.month = month;
    }

    public Double getTotalBudget() {
        return totalBudget;
    }

    public void setTotalBudget(Double totalBudget) {
        this.totalBudget = totalBudget;
    }

    public Double getTotalSpent() {
        return totalSpent;
    }

    public void setTotalSpent(Double totalSpent) {
        this.totalSpent = totalSpent;
    }

    public Double getTotalRemaining() {
        return totalRemaining;
    }

    public void setTotalRemaining(Double totalRemaining) {
        this.totalRemaining = totalRemaining;
    }

    public Double getPercentageSpent() {
        return percentageSpent;
    }

    public void setPercentageSpent(Double percentageSpent) {
        this.percentageSpent = percentageSpent;
    }

    public Integer getCategoriesCount() {
        return categoriesCount;
    }

    public void setCategoriesCount(Integer categoriesCount) {
        this.categoriesCount = categoriesCount;
    }

    public Integer getExceededCount() {
        return exceededCount;
    }

    public void setExceededCount(Integer exceededCount) {
        this.exceededCount = exceededCount;
    }

    public Integer getAlertCount() {
        return alertCount;
    }

    public void setAlertCount(Integer alertCount) {
        this.alertCount = alertCount;
    }

    public List<BudgetResponse> getBudgets() {
        return budgets;
    }

    public void setBudgets(List<BudgetResponse> budgets) {
        this.budgets = budgets;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
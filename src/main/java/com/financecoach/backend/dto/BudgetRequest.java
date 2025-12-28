// src/main/java/com/financecoach/backend/dto/BudgetRequest.java
package com.financecoach.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.LocalDate;

public class BudgetRequest {

    @NotBlank(message = "Category is required")
    private String category;

    private LocalDate month;  // Optional - defaults to current month

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    private String notes;

    @Min(value = 0, message = "Alert threshold must be between 0 and 100")
    @Max(value = 100, message = "Alert threshold must be between 0 and 100")
    private Double alertThreshold;  // Optional - defaults to 80%

    // Constructors
    public BudgetRequest() {}

    public BudgetRequest(String category, BigDecimal amount) {
        this.category = category;
        this.amount = amount;
        this.month = LocalDate.now().withDayOfMonth(1);
    }

    public BudgetRequest(String category, LocalDate month, BigDecimal amount) {
        this.category = category;
        this.month = month;
        this.amount = amount;
    }

    // Getters and Setters
    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDate getMonth() {
        return month;
    }

    public void setMonth(LocalDate month) {
        this.month = month;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Double getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(Double alertThreshold) {
        this.alertThreshold = alertThreshold;
    }
}
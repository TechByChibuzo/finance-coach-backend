// src/main/java/com/financecoach/userservice/dto/BudgetResponse.java
package com.financecoach.userservice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public class BudgetResponse {
    private UUID id;
    private String category;
    private LocalDate month;
    private Double amount;
    private Double spent;
    private Double remaining;
    private Double percentageSpent;
    private String currencyCode;
    private String notes;
    private Double alertThreshold;
    private Boolean isExceeded;
    private Boolean shouldAlert;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructors
    public BudgetResponse() {}

    public BudgetResponse(UUID id, String category, LocalDate month, Double amount,
                          Double spent, Double remaining, Double percentageSpent,
                          String currencyCode, String notes, Double alertThreshold,
                          Boolean isExceeded, Boolean shouldAlert, Boolean isActive,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.category = category;
        this.month = month;
        this.amount = amount;
        this.spent = spent;
        this.remaining = remaining;
        this.percentageSpent = percentageSpent;
        this.currencyCode = currencyCode;
        this.notes = notes;
        this.alertThreshold = alertThreshold;
        this.isExceeded = isExceeded;
        this.shouldAlert = shouldAlert;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

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

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public Double getSpent() {
        return spent;
    }

    public void setSpent(Double spent) {
        this.spent = spent;
    }

    public Double getRemaining() {
        return remaining;
    }

    public void setRemaining(Double remaining) {
        this.remaining = remaining;
    }

    public Double getPercentageSpent() {
        return percentageSpent;
    }

    public void setPercentageSpent(Double percentageSpent) {
        this.percentageSpent = percentageSpent;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
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

    public Boolean getIsExceeded() {
        return isExceeded;
    }

    public void setIsExceeded(Boolean isExceeded) {
        this.isExceeded = isExceeded;
    }

    public Boolean getShouldAlert() {
        return shouldAlert;
    }

    public void setShouldAlert(Boolean shouldAlert) {
        this.shouldAlert = shouldAlert;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
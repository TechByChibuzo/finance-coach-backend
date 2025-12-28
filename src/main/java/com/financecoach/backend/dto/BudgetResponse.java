// src/main/java/com/financecoach/userservice/dto/BudgetResponse.java
package com.financecoach.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class BudgetResponse {
    private UUID id;
    private String category;
    private LocalDate month;
    private BigDecimal amount;
    private BigDecimal spent;
    private BigDecimal remaining;
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

    public BudgetResponse(UUID id, String category, LocalDate month, BigDecimal amount,
                          BigDecimal spent, BigDecimal remaining, Double percentageSpent,
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
}
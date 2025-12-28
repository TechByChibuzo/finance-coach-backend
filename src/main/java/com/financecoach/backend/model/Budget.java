// src/main/java/com/financecoach/backend/model/Budget.java
package com.financecoach.backend.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "budgets",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "category", "month"})
        },
        indexes = {
                @Index(name = "idx_user_month", columnList = "user_id, month")
        }
)
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "month", nullable = false)
    private LocalDate month;  // First day of the month (e.g., 2024-12-01)

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(precision = 19, scale = 2)
    private BigDecimal spent;  // Amount spent so far (calculated)

    @Column(name = "currency_code")
    private String currencyCode = "USD";

    @Column(name = "notes")
    private String notes;  // Optional notes about this budget

    @Column(name = "alert_threshold")
    private Double alertThreshold = 80.0;  // Alert when spent reaches this % (default 80%)

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public Budget() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Budget(UUID userId, String category, LocalDate month, BigDecimal amount) {
        this();
        this.userId = userId;
        this.category = category;
        this.month = month;
        this.amount = amount;
    }

    // Update spent amount and updatedAt timestamp
    public void updateSpent(BigDecimal spent) {
        this.spent = spent;
        this.updatedAt = LocalDateTime.now();
    }

    // Calculate percentage spent
    public Double getPercentageSpent() {
        if (amount == null || spent == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }

        return spent
                .divide(amount, 4, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    // Check if budget is exceeded
    public Boolean isExceeded() {
        return spent != null
                && amount != null
                && spent.compareTo(amount) > 0;
    }

    // Check if alert threshold is reached
    public Boolean shouldAlert() {
        return getPercentageSpent() >= alertThreshold;
    }

    // Get remaining budget
    public BigDecimal getRemainingBudget() {
        if (amount == null) return BigDecimal.ZERO;
        if (spent == null) return amount;

        return amount.subtract(spent);
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
        this.updatedAt = LocalDateTime.now();
    }

    public BigDecimal getSpent() {
        return spent;
    }

    public void setSpent(BigDecimal spent) {
        this.spent = spent;
        this.updatedAt = LocalDateTime.now();
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
        this.updatedAt = LocalDateTime.now();
    }

    public Double getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(Double alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
        this.updatedAt = LocalDateTime.now();
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
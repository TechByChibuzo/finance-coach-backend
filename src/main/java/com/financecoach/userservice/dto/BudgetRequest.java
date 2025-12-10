// src/main/java/com/financecoach/userservice/dto/BudgetRequest.java
package com.financecoach.userservice.dto;

import java.time.LocalDate;

public class BudgetRequest {
    private String category;
    private LocalDate month;  // Optional - defaults to current month
    private Double amount;
    private String notes;
    private Double alertThreshold;  // Optional - defaults to 80%

    // Constructors
    public BudgetRequest() {}

    public BudgetRequest(String category, Double amount) {
        this.category = category;
        this.amount = amount;
        this.month = LocalDate.now().withDayOfMonth(1);
    }

    public BudgetRequest(String category, LocalDate month, Double amount) {
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

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
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
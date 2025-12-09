// src/main/java/com/financecoach/userservice/dto/plaid/TransactionResponse.java
package com.financecoach.userservice.dto.plaid;

import java.time.LocalDate;
import java.util.UUID;

public class TransactionResponse {
    private UUID id;
    private UUID accountId;
    private LocalDate date;
    private Double amount;
    private String merchantName;
    private String name;
    private String category;
    private String subcategory;
    private Boolean pending;
    private String currencyCode;

    public TransactionResponse() {}

    public TransactionResponse(UUID id, UUID accountId, LocalDate date, Double amount,
                               String merchantName, String name, String category,
                               String subcategory, Boolean pending, String currencyCode) {
        this.id = id;
        this.accountId = accountId;
        this.date = date;
        this.amount = amount;
        this.merchantName = merchantName;
        this.name = name;
        this.category = category;
        this.subcategory = subcategory;
        this.pending = pending;
        this.currencyCode = currencyCode;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public void setSubcategory(String subcategory) {
        this.subcategory = subcategory;
    }

    public Boolean getPending() {
        return pending;
    }

    public void setPending(Boolean pending) {
        this.pending = pending;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }
}
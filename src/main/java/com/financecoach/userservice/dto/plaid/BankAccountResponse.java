// src/main/java/com/financecoach/userservice/dto/plaid/BankAccountResponse.java
package com.financecoach.userservice.dto.plaid;

import java.time.LocalDateTime;
import java.util.UUID;

public class BankAccountResponse {
    private UUID id;
    private String institutionName;
    private String accountName;
    private String accountType;
    private Double currentBalance;
    private Double availableBalance;
    private String currencyCode;
    private LocalDateTime lastSyncedAt;
    private Boolean isActive;

    public BankAccountResponse() {}

    // Constructor with all fields
    public BankAccountResponse(UUID id, String institutionName, String accountName,
                               String accountType, Double currentBalance, Double availableBalance,
                               String currencyCode, LocalDateTime lastSyncedAt, Boolean isActive) {
        this.id = id;
        this.institutionName = institutionName;
        this.accountName = accountName;
        this.accountType = accountType;
        this.currentBalance = currentBalance;
        this.availableBalance = availableBalance;
        this.currencyCode = currencyCode;
        this.lastSyncedAt = lastSyncedAt;
        this.isActive = isActive;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public Double getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(Double currentBalance) {
        this.currentBalance = currentBalance;
    }

    public Double getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(Double availableBalance) {
        this.availableBalance = availableBalance;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(LocalDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
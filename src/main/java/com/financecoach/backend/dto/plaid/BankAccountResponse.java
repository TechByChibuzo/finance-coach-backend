// src/main/java/com/financecoach/backend/dto/plaid/BankAccountResponse.java
package com.financecoach.backend.dto.plaid;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class BankAccountResponse {
    private UUID id;
    private String institutionName;
    private String accountName;
    private String accountType;
    private BigDecimal currentBalance;
    private BigDecimal availableBalance;
    private String currencyCode;
    private LocalDateTime lastSyncedAt;
    private Boolean isActive;

    public BankAccountResponse() {}

    // Constructor with all fields
    public BankAccountResponse(UUID id, String institutionName, String accountName,
                               String accountType, BigDecimal currentBalance, BigDecimal availableBalance,
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


}
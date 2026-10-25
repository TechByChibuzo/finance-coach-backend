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
    private String mask;
    // The bank connection (Plaid item) this account came through, and whether the bank wants
    // the user to sign in again before it shares more data
    private UUID itemId;
    private boolean needsReconnect;
    // The bank's logo as a data URL, and its brand colour, when Plaid has them
    private String institutionLogo;
    private String institutionColor;

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
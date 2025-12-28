// src/main/java/com/financecoach/backend/model/BankAccount.java
package com.financecoach.backend.model;

import com.financecoach.backend.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plaid_account_id", nullable = false)
    private String plaidAccountId;

    @Column(name = "plaid_access_token", nullable = false)
    @Convert(converter = EncryptedStringConverter.class)
    private String plaidAccessToken;

    @Column(name = "institution_name")
    private String institutionName;

    @Column(name = "institution_id")
    private String institutionId;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "account_type")
    private String accountType;  // checking, savings, credit

    @Column(name = "account_subtype")
    private String accountSubtype;

    @Column(name = "current_balance")
    private BigDecimal currentBalance;

    @Column(name = "available_balance")
    private BigDecimal availableBalance;

    @Column(name = "currency_code")
    private String currencyCode = "USD";

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Constructors
    public BankAccount() {
        this.createdAt = LocalDateTime.now();
    }

}
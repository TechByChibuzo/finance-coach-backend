package com.financecoach.backend.dto.plaid;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
@Data
public class TransactionResponse {
    private UUID id;
    private UUID accountId;
    private LocalDate date;
    private BigDecimal amount;
    private String merchantName;
    private String name;
    private String category;
    private String subcategory;
    private Boolean pending;
    private String currencyCode;
    // Name the user gave the account when importing a CSV; null for bank-synced transactions
    private String importAccountName;
    // Merchant logo from Plaid, when known
    private String logoUrl;
    private String notes;
    // Hidden by the user, or split into parts: listed but not counted in totals
    private boolean hidden;
    private boolean split;
    // For a part of a split transaction: the transaction it was split from
    private UUID splitFromId;

    public TransactionResponse() {}

    public TransactionResponse(UUID id, UUID accountId, LocalDate date, BigDecimal amount,
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


}
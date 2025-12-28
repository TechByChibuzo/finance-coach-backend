// src/main/java/com/financecoach/backend/model/Transaction.java
package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_user_date", columnList = "user_id, date"),
        @Index(name = "idx_plaid_transaction", columnList = "plaid_transaction_id")
})
@Data
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "plaid_transaction_id", unique = true, nullable = false)
    private String plaidTransactionId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "name")  // Transaction description from Plaid
    private String name;

    @Column(name = "category")
    private String category;

    @Column(name = "subcategory")
    private String subcategory;

    @Column(name = "payment_channel")
    private String paymentChannel;  // online, in store, etc.

    @Column(name = "pending")
    private Boolean pending = false;

    @Column(name = "currency_code")
    private String currencyCode = "USD";

    @Column(name = "location_address")
    private String locationAddress;

    @Column(name = "location_city")
    private String locationCity;

    @Column(name = "location_region")
    private String locationRegion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Constructors
    public Transaction() {
        this.createdAt = LocalDateTime.now();
    }
}
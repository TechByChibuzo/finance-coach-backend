package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "holdings",
        indexes = {
                @Index(name = "idx_holdings_user", columnList = "user_id"),
                @Index(name = "idx_holdings_account", columnList = "account_id"),
                @Index(name = "idx_holdings_symbol", columnList = "symbol")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_holding_account_security", columnNames = {"account_id", "security_id"})
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "security_id", nullable = false)
    private String securityId;  // Plaid's security identifier

    @Column(length = 20)
    private String symbol;  // AAPL, TSLA, BTC-USD

    @Column(nullable = false)
    private String name;  // Apple Inc., Tesla Inc.

    @Column(length = 50, nullable = false)
    private String type;  // stock, etf, crypto, mutual_fund, bond

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;  // 10.5 shares

    @Column(name = "cost_basis", precision = 18, scale = 2)
    private BigDecimal costBasis;  // What you paid (if available)

    @Column(name = "current_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentPrice;  // Current market price per share

    @Column(name = "current_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentValue;  // quantity * currentPrice

    @Column(name = "institution_price", precision = 18, scale = 2)
    private BigDecimal institutionPrice;  // Price from brokerage (may differ from market)

    @Column(name = "institution_value", precision = 18, scale = 2)
    private BigDecimal institutionValue;  // Value from brokerage

    @Column(length = 10)
    private String currency;  // USD, EUR, etc.

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private BankAccount account;

    // Helper methods
    public BigDecimal getGainLoss() {
        if (costBasis == null) return null;
        return currentValue.subtract(costBasis);
    }

    public BigDecimal getGainLossPercentage() {
        if (costBasis == null || costBasis.compareTo(BigDecimal.ZERO) == 0) return null;
        return getGainLoss()
                .divide(costBasis, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(new BigDecimal("100"));
    }
}
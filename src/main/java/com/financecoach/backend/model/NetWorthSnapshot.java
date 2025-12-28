package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "net_worth_snapshots",
        indexes = {
                @Index(name = "idx_networth_user_date", columnList = "user_id, snapshot_date")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_networth_user_date", columnNames = {"user_id", "snapshot_date"})
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetWorthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "total_assets", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAssets;

    @Column(name = "cash_balance", precision = 18, scale = 2)
    private BigDecimal cashBalance;

    @Column(name = "investments_value", precision = 18, scale = 2)
    private BigDecimal investmentsValue;

    @Column(name = "manual_assets_value", precision = 18, scale = 2)
    private BigDecimal manualAssetsValue;

    @Column(name = "total_liabilities", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalLiabilities;

    @Column(name = "credit_card_debt", precision = 18, scale = 2)
    private BigDecimal creditCardDebt;

    @Column(name = "manual_liabilities", precision = 18, scale = 2)
    private BigDecimal manualLiabilities;

    @Column(name = "net_worth", nullable = false, precision = 18, scale = 2)
    private BigDecimal netWorth;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
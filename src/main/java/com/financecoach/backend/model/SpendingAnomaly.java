package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "spending_anomalies", indexes = {
        @Index(name = "idx_anomaly_user_id", columnList = "user_id"),
        @Index(name = "idx_anomaly_detected_at", columnList = "detected_at")
})
public class SpendingAnomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "current_spend", nullable = false)
    private BigDecimal currentSpend;

    @Column(name = "average_spend", nullable = false)
    private BigDecimal averageSpend;

    @Column(name = "standard_deviation", nullable = false)
    private BigDecimal standardDeviation;

    @Column(name = "z_score", nullable = false)
    private BigDecimal zScore;

    @Column(name = "severity")
    private String severity; // LOW, MEDIUM, HIGH

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "detected_at")
    private final LocalDateTime detectedAt = LocalDateTime.now();

    @Column(name = "is_reviewed")
    private Boolean isReviewed = false;

    public SpendingAnomaly() {}

    public SpendingAnomaly(UUID userId, String category,
                           BigDecimal currentSpend, BigDecimal averageSpend,
                           BigDecimal standardDeviation, BigDecimal zScore,
                           String severity, LocalDate periodStart, LocalDate periodEnd) {
        this.userId = userId;
        this.category = category;
        this.currentSpend = currentSpend;
        this.averageSpend = averageSpend;
        this.standardDeviation = standardDeviation;
        this.zScore = zScore;
        this.severity = severity;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    public void markAsReviewed() {
        this.isReviewed = true;
    }

    /**
     * Update the figures as the month progresses. If the anomaly got more severe,
     * show it to the user again even if they had already reviewed it.
     */
    public void refresh(BigDecimal currentSpend, BigDecimal averageSpend,
                        BigDecimal standardDeviation, BigDecimal zScore, String severity,
                        LocalDate periodEnd) {
        if (severityRank(severity) > severityRank(this.severity)) {
            this.isReviewed = false;
        }
        this.periodEnd = periodEnd;
        this.currentSpend = currentSpend;
        this.averageSpend = averageSpend;
        this.standardDeviation = standardDeviation;
        this.zScore = zScore;
        this.severity = severity;
    }

    private static int severityRank(String severity) {
        if ("HIGH".equals(severity)) return 3;
        if ("MEDIUM".equals(severity)) return 2;
        if ("LOW".equals(severity)) return 1;
        return 0;
    }
}
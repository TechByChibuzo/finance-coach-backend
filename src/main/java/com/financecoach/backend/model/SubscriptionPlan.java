package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscription_plans")
@Data
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String name; // FREE, PREMIUM, PRO

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceMonthly = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceYearly = BigDecimal.ZERO;

    @Column(name = "stripe_price_id_monthly")
    private String stripePriceIdMonthly;

    @Column(name = "stripe_price_id_yearly")
    private String stripePriceIdYearly;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String features; // JSON string of features

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String limits; // JSON string of limits

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    // Helper methods
    public boolean isFree() {
        return "FREE".equalsIgnoreCase(name);
    }

    public boolean isPremium() {
        return "PREMIUM".equalsIgnoreCase(name);
    }

    public boolean isPro() {
        return "PRO".equalsIgnoreCase(name);
    }
}
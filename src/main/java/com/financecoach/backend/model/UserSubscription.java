package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_subscriptions")
@Data
public class UserSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status; // ACTIVE, CANCELLED, EXPIRED, TRIAL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingCycle billingCycle; // MONTHLY, YEARLY

    // Dates
    @Column(nullable = false)
    private LocalDateTime startDate = LocalDateTime.now();

    private LocalDateTime endDate;
    private LocalDateTime trialEndDate;
    private LocalDateTime cancelledAt;

    // Stripe integration
    @Column(length = 255)
    private String stripeCustomerId;

    @Column(length = 255)
    private String stripeSubscriptionId;

    // Auto-renewal
    @Column(nullable = false)
    private Boolean autoRenew = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    // Helper methods
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE &&
                (endDate == null || endDate.isAfter(LocalDateTime.now()));
    }

    public boolean isExpired() {
        return status == SubscriptionStatus.EXPIRED ||
                (endDate != null && endDate.isBefore(LocalDateTime.now()));
    }

    public boolean isTrial() {
        return status == SubscriptionStatus.TRIAL &&
                trialEndDate != null &&
                trialEndDate.isAfter(LocalDateTime.now());
    }

    public long getDaysRemaining() {
        if (endDate == null) return -1;
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), endDate);
    }
}
package com.financecoach.backend.dto;

import com.financecoach.backend.model.BillingCycle;
import com.financecoach.backend.model.SubscriptionStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class SubscriptionResponse {
    private UUID id;
    private String planName;
    private String planDisplayName;
    private SubscriptionStatus status;
    private BillingCycle billingCycle;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Long daysRemaining;
    private Boolean autoRenew;
}
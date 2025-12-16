package com.financecoach.backend.dto;

import com.financecoach.backend.model.BillingCycle;
import lombok.Data;

import java.util.UUID;

@Data
public class CheckoutRequest {
    private UUID planId;           // ID of the subscription plan
    private String planName;       // Alternative: name of the plan (FREE, PREMIUM, PRO)
    private BillingCycle billingCycle;  // MONTHLY or YEARLY
}
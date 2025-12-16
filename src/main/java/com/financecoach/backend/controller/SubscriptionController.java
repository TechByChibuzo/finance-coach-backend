// ============================================
// UPDATED SubscriptionController
// Uses your existing JWT authentication
// ============================================

package com.financecoach.backend.controller;

import com.financecoach.backend.dto.*;
import com.financecoach.backend.model.*;
import com.financecoach.backend.service.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/subscriptions")
@CrossOrigin(origins = "http://localhost:5173")
public class SubscriptionController {

    @Autowired
    private SubscriptionService subscriptionService;

    /**
     * Get current authenticated user's ID from SecurityContext
     * This is automatically populated by your JwtAuthenticationFilter
     */
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }

        // Your JwtAuthenticationFilter sets the UUID as the principal
        return (UUID) authentication.getPrincipal();
    }

    /**
     * Get all available plans (PUBLIC - no auth required)
     */
    @GetMapping("/plans")
    public ResponseEntity<List<PlanResponse>> getPlans() {
        List<SubscriptionPlan> plans = subscriptionService.getAllPlans();
        List<PlanResponse> responses = plans.stream()
                .map(this::toPlanResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * Get current user's subscription
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentSubscription() {
        UUID userId = getCurrentUserId();

        return subscriptionService.getUserSubscription(userId)
                .map(sub -> ResponseEntity.ok(toSubscriptionResponse(sub)))
                .orElse(ResponseEntity.ok(getDefaultFreeResponse(userId)));
    }

    /**
     * Check feature access for current user
     */
    @GetMapping("/features/{featureName}/access")
    public ResponseEntity<FeatureAccessResponse> checkFeatureAccess(
            @PathVariable String featureName) {

        UUID userId = getCurrentUserId();

        boolean hasAccess = subscriptionService.hasFeatureAccess(userId, featureName);
        boolean canUse = subscriptionService.canUseFeature(userId, featureName);
        Integer remaining = subscriptionService.getRemainingUsage(userId, featureName);

        if (!hasAccess) {
            return ResponseEntity.ok(
                    FeatureAccessResponse.denied("Upgrade to access this feature")
            );
        }

        if (!canUse) {
            return ResponseEntity.ok(
                    FeatureAccessResponse.limitExceeded("Usage limit exceeded. Upgrade for unlimited access.")
            );
        }

        return ResponseEntity.ok(FeatureAccessResponse.granted(remaining));
    }

    /**
     * Create Stripe checkout session
     */
    @PostMapping("/checkout")
    public ResponseEntity<?> createCheckout(@RequestBody CheckoutRequest request) {
        UUID userId = getCurrentUserId();

        try {
            String checkoutUrl = subscriptionService.createCheckoutSession(
                    userId,
                    request.getPlanId(),
                    request.getBillingCycle()
            );

            return ResponseEntity.ok(Map.of("url", checkoutUrl));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Cancel current user's subscription
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancelSubscription() {
        UUID userId = getCurrentUserId();

        try {
            subscriptionService.cancelSubscription(userId);
            return ResponseEntity.ok(Map.of("message", "Subscription cancelled successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Stripe webhook handler (PUBLIC - authenticated by Stripe signature)
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        try {
            subscriptionService.handleStripeWebhook(payload, sigHeader);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ============================================
    // HELPER METHODS - Convert entities to DTOs
    // ============================================

    private SubscriptionResponse toSubscriptionResponse(UserSubscription sub) {
        SubscriptionResponse response = new SubscriptionResponse();
        response.setId(sub.getId());
        response.setPlanName(sub.getPlan().getName());
        response.setPlanDisplayName(sub.getPlan().getDisplayName());
        response.setStatus(sub.getStatus());
        response.setBillingCycle(sub.getBillingCycle());
        response.setStartDate(sub.getStartDate());
        response.setEndDate(sub.getEndDate());
        response.setDaysRemaining(sub.getDaysRemaining());
        response.setAutoRenew(sub.getAutoRenew());
        return response;
    }

    private SubscriptionResponse getDefaultFreeResponse(UUID userId) {
        SubscriptionResponse response = new SubscriptionResponse();
        SubscriptionPlan freePlan = subscriptionService.getUserPlan(userId);
        response.setPlanName(freePlan.getName());
        response.setPlanDisplayName(freePlan.getDisplayName());
        response.setStatus(SubscriptionStatus.ACTIVE);
        return response;
    }

    private PlanResponse toPlanResponse(SubscriptionPlan plan) {
        PlanResponse response = new PlanResponse();
        response.setId(plan.getId());
        response.setName(plan.getName());
        response.setDisplayName(plan.getDisplayName());
        response.setDescription(plan.getDescription());
        response.setPriceMonthly(plan.getPriceMonthly());
        response.setPriceYearly(plan.getPriceYearly());
        return response;
    }
}
// ============================================
// UPDATED SubscriptionController
// Uses your existing JWT authentication
// ============================================

package com.financecoach.backend.controller;

import com.financecoach.backend.exception.BillingUnavailableException;
import com.stripe.exception.StripeException;
import com.financecoach.backend.dto.*;
import com.financecoach.backend.model.*;
import com.financecoach.backend.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionController.class);

    @Autowired
    private SubscriptionService subscriptionService;

    /**
     * Get current authenticated user's ID from SecurityContext
     * This is automatically populated by your JwtAuthenticationFilter
     */
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("User not authenticated");
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

        // Errors go to GlobalExceptionHandler, which picks the status and a safe message;
        // Stripe's own error text is only logged
        try {
            String checkoutUrl = subscriptionService.createCheckoutSession(
                    userId,
                    request.getPlanId(),
                    request.getBillingCycle()
            );
            return ResponseEntity.ok(Map.of("url", checkoutUrl));
        } catch (StripeException e) {
            throw new BillingUnavailableException("Couldn't start checkout. Please try again.", e);
        }
    }

    /**
     * Cancel current user's subscription
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancelSubscription() {
        UUID userId = getCurrentUserId();

        UserSubscription subscription = subscriptionService.cancelSubscription(userId);

        if (subscription.getStatus() == SubscriptionStatus.ACTIVE && subscription.getEndDate() != null) {
            return ResponseEntity.ok(Map.of(
                    "message", "Subscription cancelled. You'll keep access until the end of your billing period.",
                    "accessUntil", subscription.getEndDate().toString()
            ));
        }
        return ResponseEntity.ok(Map.of("message", "Subscription cancelled successfully"));
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
        } catch (IllegalArgumentException e) {
            // Bad signature - not from Stripe
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            // Non-2xx makes Stripe retry the event later
            logger.error("Failed to process Stripe webhook", e);
            return ResponseEntity.internalServerError().build();
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
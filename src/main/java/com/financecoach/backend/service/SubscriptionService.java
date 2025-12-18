// ============================================
// COMPLETE SubscriptionService.java
// With all methods needed for SubscriptionController
// ============================================

package com.financecoach.backend.service;

import com.financecoach.backend.model.*;
import com.financecoach.backend.repository.*;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.model.Customer;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SubscriptionService {

    @Autowired
    private UserSubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionPlanRepository planRepository;

    @Autowired
    private UsageTrackingRepository usageRepository;

    @Autowired
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StripeService stripeService;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    // ============================================
    // PLAN MANAGEMENT
    // ============================================

    /**
     * Get all active subscription plans
     */
    public List<SubscriptionPlan> getAllPlans() {
        return planRepository.findByIsActiveTrue();
    }

    /**
     * Get plan by ID
     */
    public Optional<SubscriptionPlan> getPlanById(UUID planId) {
        return planRepository.findById(planId);
    }

    /**
     * Get plan by name
     */
    public Optional<SubscriptionPlan> getPlanByName(String planName) {
        return planRepository.findByName(planName);
    }

    // ============================================
    // USER SUBSCRIPTION MANAGEMENT
    // ============================================

    /**
     * Get user's current active subscription
     */
    public Optional<UserSubscription> getUserSubscription(UUID userId) {
        return subscriptionRepository.findActiveByUserId(userId);
    }

    /**
     * Get user's subscription plan (or default to FREE)
     */
    public SubscriptionPlan getUserPlan(UUID userId) {
        return getUserSubscription(userId)
                .map(UserSubscription::getPlan)
                .orElse(getFreePlan());
    }

    // ============================================
    // FEATURE ACCESS CONTROL
    // ============================================

    /**
     * Check if user has access to a feature
     */
    public boolean hasFeatureAccess(UUID userId, String featureName) {
        // Get feature flag
        Optional<FeatureFlag> flag = featureFlagRepository.findByFeatureName(featureName);
        if (flag.isEmpty() || !flag.get().getIsEnabled()) {
            return false; // Feature disabled globally
        }

        // Check required plan
        FeatureFlag feature = flag.get();
        if (feature.getRequiredPlan() == null) {
            return true; // No plan required
        }

        // Get user's plan
        SubscriptionPlan userPlan = getUserPlan(userId);

        // Check plan hierarchy: PRO > PREMIUM > FREE
        return hasRequiredPlanLevel(userPlan.getName(), feature.getRequiredPlan());
    }

    /**
     * Check if user can use a feature (considering usage limits)
     */
    public boolean canUseFeature(UUID userId, String featureName) {
        // First check basic access
        if (!hasFeatureAccess(userId, featureName)) {
            return false;
        }

        // Check usage limits
        SubscriptionPlan plan = getUserPlan(userId);
        Integer usageLimit = getFeatureLimit(plan, featureName);

        if (usageLimit == null || usageLimit == -1) {
            return true; // Unlimited
        }

        // Check current usage
        Integer currentUsage = getCurrentUsage(userId, featureName);
        return currentUsage < usageLimit;
    }

    // ============================================
    // USAGE TRACKING
    // ============================================

    /**
     * Track feature usage
     */
    @Transactional
    public void trackUsage(UUID userId, String featureName) {
        LocalDate today = LocalDate.now();
        LocalDate startOfMonth = today.withDayOfMonth(1);
        LocalDate endOfMonth = today.withDayOfMonth(today.lengthOfMonth());

        Optional<UsageTracking> existing = usageRepository
                .findByUserIdAndFeatureNameAndPeriod(userId, featureName, startOfMonth, endOfMonth);

        if (existing.isPresent()) {
            // Increment existing
            UsageTracking usage = existing.get();
            usage.setUsageCount(usage.getUsageCount() + 1);
            usageRepository.save(usage);
        } else {
            // Create new
            UsageTracking usage = new UsageTracking();
            usage.setUserId(userId);
            usage.setFeatureName(featureName);
            usage.setUsageCount(1);
            usage.setPeriodStart(startOfMonth);
            usage.setPeriodEnd(endOfMonth);
            usageRepository.save(usage);
        }
    }

    /**
     * Get current usage count for a feature
     */
    public Integer getCurrentUsage(UUID userId, String featureName) {
        LocalDate today = LocalDate.now();
        LocalDate startOfMonth = today.withDayOfMonth(1);
        LocalDate endOfMonth = today.withDayOfMonth(today.lengthOfMonth());

        return usageRepository
                .findByUserIdAndFeatureNameAndPeriod(userId, featureName, startOfMonth, endOfMonth)
                .map(UsageTracking::getUsageCount)
                .orElse(0);
    }

    /**
     * Get remaining usage for a feature
     */
    public Integer getRemainingUsage(UUID userId, String featureName) {
        SubscriptionPlan plan = getUserPlan(userId);
        Integer limit = getFeatureLimit(plan, featureName);

        if (limit == null || limit == -1) {
            return -1; // Unlimited
        }

        Integer current = getCurrentUsage(userId, featureName);
        return Math.max(0, limit - current);
    }

    // ============================================
    // CHECKOUT & PAYMENT
    // ============================================

    /**
     * Create Stripe checkout session
     */
    @Transactional
    public String createCheckoutSession(UUID userId, UUID planId, BillingCycle billingCycle)
            throws StripeException {

        // Get plan
        SubscriptionPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        // Get or create Stripe customer
        String stripeCustomerId = getOrCreateStripeCustomer(userId);

        // Get price based on billing cycle
        String priceId = getStripePriceId(plan, billingCycle);

        String successUrl = frontendUrl + "/subscription/success?session_id={CHECKOUT_SESSION_ID}";
        String cancelUrl = frontendUrl + "/subscription/cancel";

        // Create checkout session
        Session session = stripeService.createCheckoutSession(
                stripeCustomerId,
                priceId,
                successUrl,
                cancelUrl
        );

        return session.getUrl();
    }

    /**
     * Get Stripe price ID from database
     */
    private String getStripePriceId(SubscriptionPlan plan, BillingCycle cycle) {
        String priceId = cycle == BillingCycle.MONTHLY
                ? plan.getStripePriceIdMonthly()
                : plan.getStripePriceIdYearly();

        if (priceId == null || priceId.isEmpty()) {
            throw new RuntimeException(
                    "Stripe price ID not configured for plan: " + plan.getName() +
                            " (" + cycle + ")"
            );
        }

        return priceId;
    }

    /**
     * Get or create Stripe customer for user
     */
    private String getOrCreateStripeCustomer(UUID userId) throws StripeException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getStripeCustomerId() != null) {
            return user.getStripeCustomerId();
        }

        // Create new Stripe customer
        Customer customer = stripeService.createCustomer(
                user.getEmail(),
                user.getFullName() != null ? user.getFullName() : user.getEmail()
        );

        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        System.out.println("✅ Stored customer ID in user: " + customer.getId());

        return customer.getId();
    }

    // ============================================
    // SUBSCRIPTION OPERATIONS
    // ============================================

    /**
     * Create or upgrade subscription
     */
    @Transactional
    public void createSubscription(UUID userId, String planName, BillingCycle cycle) {
        // Get plan
        SubscriptionPlan plan = planRepository.findByName(planName)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planName));

        // Cancel existing subscription
        getUserSubscription(userId).ifPresent(existing -> {
            try {
                if (existing.getStripeSubscriptionId() != null) {
                    stripeService.cancelSubscription(existing.getStripeSubscriptionId());
                }
                existing.setStatus(SubscriptionStatus.CANCELLED);
                existing.setCancelledAt(LocalDateTime.now());
                subscriptionRepository.save(existing);
            } catch (StripeException e) {
                System.err.println("Failed to cancel Stripe subscription: " + e.getMessage());
            }
        });

        // Create new subscription
        UserSubscription subscription = new UserSubscription();
        subscription.setUserId(userId);
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setBillingCycle(cycle);
        subscription.setStartDate(LocalDateTime.now());

        // Set end date based on cycle
        if (cycle == BillingCycle.MONTHLY) {
            subscription.setEndDate(LocalDateTime.now().plusMonths(1));
        } else if (cycle == BillingCycle.YEARLY) {
            subscription.setEndDate(LocalDateTime.now().plusYears(1));
        }

        subscription.setAutoRenew(true);
        subscriptionRepository.save(subscription);

        System.out.println("✅ Subscription activated for user: " + userId);
        System.out.println("✅ Plan: " + plan.getName());
        System.out.println("✅ Billing: " + cycle);
    }

    /**
     * Start free trial
     */
    @Transactional
    public UserSubscription startTrial(UUID userId, String planName, int trialDays) {
        SubscriptionPlan plan = planRepository.findByName(planName)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        UserSubscription subscription = new UserSubscription();
        subscription.setUserId(userId);
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.TRIAL);
        subscription.setBillingCycle(BillingCycle.MONTHLY);
        subscription.setStartDate(LocalDateTime.now());
        subscription.setTrialEndDate(LocalDateTime.now().plusDays(trialDays));
        subscription.setEndDate(LocalDateTime.now().plusDays(trialDays));

        return subscriptionRepository.save(subscription);
    }

    /**
     * Cancel subscription
     */
    @Transactional
    public void cancelSubscription(UUID userId) {
        UserSubscription subscription = getUserSubscription(userId)
                .orElseThrow(() -> new RuntimeException("No active subscription"));

        // Cancel in Stripe if exists
        if (subscription.getStripeSubscriptionId() != null) {
            try {
                stripeService.cancelSubscription(subscription.getStripeSubscriptionId());
                subscription.setStatus(SubscriptionStatus.CANCELLED);
                subscription.setCancelledAt(LocalDateTime.now());
                subscription.setAutoRenew(false);
            } catch (StripeException e) {
                // Log error but don't fail
                System.err.println("Failed to cancel Stripe subscription: " + e.getMessage());
            }
        }

        subscriptionRepository.save(subscription);
    }

    // ============================================
    // STRIPE WEBHOOK HANDLING
    // ============================================

    /**
     * Handle Stripe webhook events
     */
    @Transactional
    public void handleStripeWebhook(String payload, String sigHeader) {
        Event event;

        try {
            // Verify webhook signature
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new RuntimeException("Invalid webhook signature");
        }

        // Handle different event types
        switch (event.getType()) {
            case "checkout.session.completed":
                handleCheckoutCompleted(event);
                break;

            case "customer.subscription.updated":
                handleSubscriptionUpdated(event);
                break;

            case "customer.subscription.deleted":
                handleSubscriptionCancelled(event);
                break;

            case "invoice.payment_succeeded":
                handlePaymentSucceeded(event);
                break;

            case "invoice.payment_failed":
                handlePaymentFailed(event);
                break;

            default:
                System.out.println("Unhandled event type: " + event.getType());
        }
    }

    /**
     * Handle checkout.session.completed
     * This is called when user completes payment
     */
    private void handleCheckoutCompleted(Event event) {
        try {
            // Parse the session from event data
            Session session = (Session) event.getDataObjectDeserializer()
                    .getObject()
                    .orElseThrow(() -> new RuntimeException("Session not found"));

            System.out.println("Processing checkout session: " + session.getId());
            System.out.println("Customer: " + session.getCustomer());
            System.out.println("Subscription: " + session.getSubscription());

            // Get subscription ID from session
            String stripeSubscriptionId = session.getSubscription();
            String stripeCustomerId = session.getCustomer();

            if (stripeSubscriptionId == null) {
                System.out.println("No subscription in session, skipping");
                return;
            }

            // Fetch full subscription details from Stripe
            Subscription stripeSubscription =
                    com.stripe.model.Subscription.retrieve(stripeSubscriptionId);

            // Get the plan from subscription
            String stripePriceId = stripeSubscription.getItems().getData().get(0).getPrice().getId();

            // Find matching plan in database
            SubscriptionPlan plan = findPlanByStripePriceId(stripePriceId);

            // Determine billing cycle
            BillingCycle billingCycle = stripeSubscription.getItems().getData().get(0)
                    .getPrice().getRecurring().getInterval().equals("year")
                    ? BillingCycle.YEARLY
                    : BillingCycle.MONTHLY;

            // Find user by Stripe customer ID or email
            UUID userId = findUserByStripeCustomerId(stripeCustomerId);

            if (userId == null) {
                System.err.println("User not found for Stripe customer: " + stripeCustomerId);
                return;
            }

            System.out.println("✅ Found user: " + userId);
            createSubscription(userId, plan.getName(), billingCycle);

        } catch (Exception e) {
            System.err.println("Error handling checkout completed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void handleSubscriptionUpdated(Event event) {
        System.out.println("Subscription updated: " + event.getId());
    }

    private void handleSubscriptionCancelled(Event event) {
        System.out.println("Subscription cancelled: " + event.getId());
    }

    private void handlePaymentSucceeded(Event event) {
        System.out.println("Payment succeeded: " + event.getId());
    }

    private void handlePaymentFailed(Event event) {
        System.out.println("Payment failed: " + event.getId());
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    private SubscriptionPlan getFreePlan() {
        return planRepository.findByName("FREE")
                .orElseThrow(() -> new RuntimeException("FREE plan not found"));
    }

    private boolean hasRequiredPlanLevel(String userPlan, String requiredPlan) {
        int userLevel = getPlanLevel(userPlan);
        int requiredLevel = getPlanLevel(requiredPlan);
        return userLevel >= requiredLevel;
    }

    private int getPlanLevel(String planName) {
        return switch (planName.toUpperCase()) {
            case "PRO" -> 3;
            case "PREMIUM" -> 2;
            case "FREE" -> 1;
            default -> 0;
        };
    }

    private Integer getFeatureLimit(SubscriptionPlan plan, String featureName) {
        // Parse limits JSON and get specific feature limit
        // This is simplified - in real app, parse the actual JSON
        return switch (featureName) {
            case "ai_coach_message" -> plan.isFree() ? 5 : -1;
            case "budget_create" -> plan.isFree() ? 3 : -1;
            case "bank_account" -> plan.isFree() ? 1 : (plan.isPremium() ? 5 : -1);
            case "report_export" -> plan.isFree() ? 0 : -1;
            default -> -1; // Unlimited by default
        };
    }

    // ============================================
    // HELPER METHODS FOR WEBHOOK HANDLING
    // ============================================

    /**
     * Find plan by Stripe price ID
     */
    private SubscriptionPlan findPlanByStripePriceId(String stripePriceId) {
        return planRepository.findAll().stream()
                .filter(plan ->
                        stripePriceId.equals(plan.getStripePriceIdMonthly()) ||
                                stripePriceId.equals(plan.getStripePriceIdYearly())
                )
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Plan not found for price: " + stripePriceId));
    }

    /**
     * Find user by Stripe customer ID
     */
    private UUID findUserByStripeCustomerId(String stripeCustomerId) {
        return userRepository.findByStripeCustomerId(stripeCustomerId)
                .map(User::getId)
                .orElse(null);
    }
}
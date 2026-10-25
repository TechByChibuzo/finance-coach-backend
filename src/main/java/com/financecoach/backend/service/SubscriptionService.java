package com.financecoach.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.scheduling.annotation.Scheduled;
import com.financecoach.backend.repository.ProcessedStripeEventRepository;
import com.financecoach.backend.exception.BillingUnavailableException;
import com.financecoach.backend.exception.ResourceNotFoundException;
import com.financecoach.backend.exception.UsageLimitExceededException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.*;
import com.financecoach.backend.model.PlanTier;
import com.financecoach.backend.repository.*;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.model.Customer;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);

    private final UserSubscriptionRepository subscriptionRepository;

    private final SubscriptionPlanRepository planRepository;

    private final UsageTrackingRepository usageRepository;

    private final FeatureFlagRepository featureFlagRepository;

    private final PaymentRepository paymentRepository;

    private final UserRepository userRepository;

    private final StripeService stripeService;
    private final ProcessedStripeEventRepository processedEventRepository;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public SubscriptionService(UserSubscriptionRepository subscriptionRepository,
                               SubscriptionPlanRepository planRepository,
                               UsageTrackingRepository usageRepository,
                               FeatureFlagRepository featureFlagRepository,
                               PaymentRepository paymentRepository,
                               UserRepository userRepository,
                               StripeService stripeService,
                               ProcessedStripeEventRepository processedEventRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.usageRepository = usageRepository;
        this.featureFlagRepository = featureFlagRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.stripeService = stripeService;
        this.processedEventRepository = processedEventRepository;
    }

    // ============================================
    // PLAN MANAGEMENT
    // ============================================

    /**
     * Get all active subscription plans
     */
    public List<SubscriptionPlan> getAllPlans() {
        logger.debug("Fetching all subscription plans");
        return planRepository.findByIsActiveTrue();
    }

    /**
     * Get plan by ID
     */
    public Optional<SubscriptionPlan> getPlanById(UUID planId) {
        logger.debug("Fetching plan by ID: {}", planId);
        return planRepository.findById(planId);
    }

    /**
     * Get plan by name
     */
    public Optional<SubscriptionPlan> getPlanByName(String planName) {
        logger.debug("Fetching plan by name: {}", planName);
        return planRepository.findByName(planName);
    }

    // ============================================
    // USER SUBSCRIPTION MANAGEMENT
    // ============================================

    /**
     * Get user's current active subscription
     */
    public Optional<UserSubscription> getUserSubscription(UUID userId) {
        logger.debug("Fetching active subscription for user: {}", userId);
        return subscriptionRepository.findActiveByUserId(userId);
    }

    /**
     * Get user's subscription plan (or default to FREE)
     */
    public SubscriptionPlan getUserPlan(UUID userId) {
        logger.debug("Getting user plan for: {}", userId);
        SubscriptionPlan plan = getUserSubscription(userId)
                .map(UserSubscription::getPlan)
                .orElse(getFreePlan());
        logger.debug("User {} is on plan: {}", userId, plan.getName());
        return plan;

    }

    // ============================================
    // FEATURE ACCESS CONTROL
    // ============================================

    /**
     * Check if user has access to a feature
     */
    public boolean hasFeatureAccess(UUID userId, String featureName) {
        logger.debug("Checking feature access for user: {}, feature: {}", userId, featureName);

        // Get feature flag
        Optional<FeatureFlag> flag = featureFlagRepository.findByFeatureName(featureName);
        if (flag.isEmpty() || !flag.get().getIsEnabled()) {
            logger.warn("Feature disabled or not found: {}", featureName);
            return false; // Feature disabled globally
        }

        // Check required plan
        FeatureFlag feature = flag.get();
        if (feature.getRequiredPlan() == null) {
            logger.debug("Feature {} requires no plan, access granted", featureName);
            return true; // No plan required
        }

        // Get user's plan
        SubscriptionPlan userPlan = getUserPlan(userId);

        return PlanTier.meets(userPlan.getName(), feature.getRequiredPlan());
    }

    /**
     * Check if user can use a feature (considering usage limits)
     */
    public boolean canUseFeature(UUID userId, String featureName) {
        logger.debug("Checking if user {} can use feature: {}", userId, featureName);

        // First check basic access
        if (!hasFeatureAccess(userId, featureName)) {
            logger.warn("User {} does not have access to feature: {}", userId, featureName);
            return false;
        }

        // Check usage limits
        SubscriptionPlan plan = getUserPlan(userId);
        Integer usageLimit = getFeatureLimit(plan, featureName);

        if (usageLimit == null || usageLimit == -1) {
            logger.debug("Feature {} has unlimited usage for user: {}", featureName, userId);
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
     * Atomically count one use of a feature for this month, unless the user is at their limit.
     *
     * @throws UsageLimitExceededException if the plan's limit for this month is already used up
     */
    public void reserveUsage(UUID userId, String featureName) {
        Integer limit = getFeatureLimit(getUserPlan(userId), featureName);
        boolean unlimited = limit == null || limit == -1;
        if (!unlimited && limit <= 0) {
            throw new UsageLimitExceededException("Usage limit exceeded for " + featureName);
        }

        LocalDate today = LocalDate.now();
        int counted = usageRepository.reserveUsage(userId, featureName,
                today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()),
                unlimited ? Integer.MAX_VALUE : limit);

        if (counted == 0) {
            throw new UsageLimitExceededException("Usage limit exceeded for " + featureName);
        }
    }

    /**
     * Give back a use reserved by reserveUsage, when the request it was for failed
     */
    public void releaseUsage(UUID userId, String featureName) {
        usageRepository.releaseUsage(userId, featureName, LocalDate.now().withDayOfMonth(1));
    }

    /**
     * The user's plan limit for a feature or countable resource (budgets per month, bank
     * connections, monthly AI messages). -1 means unlimited.
     */
    public int getLimit(UUID userId, String featureName) {
        Integer limit = getFeatureLimit(getUserPlan(userId), featureName);
        return limit == null ? -1 : limit;
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

        logger.info("Creating checkout session - User: {}, Plan: {}, Cycle: {}",
                userId, planId, billingCycle);

        // Get plan
        SubscriptionPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> {
                    logger.error("Plan not found: {}", planId);
                    return new ResourceNotFoundException("Plan");
                });

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
                cancelUrl,
                userId.toString()
        );

        logger.info("Checkout session created successfully - User: {}, SessionId: {}",
                userId, session.getId());

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
            logger.error("Stripe price ID not configured for plan: {} ({})",
                    plan.getName(), cycle);
            throw new RuntimeException(
                    "Stripe price ID not configured for plan: " + plan.getName() +
                            " (" + cycle + ")"
            );
        }

        logger.debug("Retrieved Stripe price ID: {} for plan: {}", priceId, plan.getName());
        return priceId;
    }

    /**
     * Get or create Stripe customer for user
     */
    private String getOrCreateStripeCustomer(UUID userId) throws StripeException {
        logger.debug("Getting or creating Stripe customer for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    logger.error("User not found: {}", userId);
                    return new RuntimeException("User not found");
                });

        if (user.getStripeCustomerId() != null) {
            logger.debug("Existing Stripe customer found: {}", user.getStripeCustomerId());
            return user.getStripeCustomerId();
        }

        // Create new Stripe customer
        Customer customer = stripeService.createCustomer(
                user.getEmail(),
                user.getFullName() != null ? user.getFullName() : user.getEmail()
        );

        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);

        logger.info("Created new Stripe customer - User: {}, CustomerId: {}",
                userId, customer.getId());

        return customer.getId();
    }

    // ============================================
    // SUBSCRIPTION OPERATIONS
    // ============================================

    /**
     * Create or upgrade subscription from a paid Stripe subscription
     */
    @Transactional
    public void createSubscription(UUID userId, SubscriptionPlan plan, BillingCycle cycle,
                                   Subscription stripeSubscription) {
        logger.info("Creating subscription - User: {}, Plan: {}, Cycle: {}, StripeSubscription: {}",
                userId, plan.getName(), cycle, stripeSubscription.getId());

        // Stripe sends several events for one new subscription at once (checkout.session.completed,
        // customer.subscription.created, invoice.payment_succeeded). Serialize them per user, so the
        // later ones wait, see the subscription already recorded below and skip it, instead of each
        // recording it and cancelling the previous plan.
        userRepository.lockById(userId);
        Optional<UserSubscription> current = getUserSubscription(userId);

        // Already recorded (e.g. Stripe retried the webhook) - don't cancel the subscription we just created
        if (current.isPresent()
                && stripeSubscription.getId().equals(current.get().getStripeSubscriptionId())) {
            logger.info("Stripe subscription {} already recorded, skipping", stripeSubscription.getId());
            return;
        }

        // Cancel the subscription being replaced. If Stripe can't cancel it, fail: carrying on would
        // leave the user billed for both plans with two ACTIVE rows. The webhook then returns an
        // error and Stripe retries the event, and cancelSubscription is safe to repeat.
        current.ifPresent(existing -> {
            logger.info("Cancelling existing subscription: {}", existing.getId());
            // Older rows never stored the Stripe ID - cancel whatever else the customer is still billed for
            List<String> toCancel = existing.getStripeSubscriptionId() != null
                    ? List.of(existing.getStripeSubscriptionId())
                    : findOtherRunningStripeSubscriptionIds(stripeSubscription);
            for (String stripeSubscriptionId : toCancel) {
                try {
                    stripeService.cancelSubscription(stripeSubscriptionId);
                } catch (StripeException e) {
                    throw new IllegalStateException("Could not cancel previous Stripe subscription "
                            + stripeSubscriptionId + "; the event will be retried", e);
                }
            }
            existing.setStatus(SubscriptionStatus.CANCELLED);
            existing.setCancelledAt(LocalDateTime.now());
            subscriptionRepository.save(existing);
        });

        // Create new subscription
        UserSubscription subscription = new UserSubscription();
        subscription.setUserId(userId);
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setBillingCycle(cycle);
        subscription.setStartDate(LocalDateTime.now());
        subscription.setStripeSubscriptionId(stripeSubscription.getId());
        subscription.setEndDate(getCurrentPeriodEnd(stripeSubscription, cycle));
        subscription.setAutoRenew(true);
        subscriptionRepository.save(subscription);

        logger.info("Subscription created successfully - User: {}, Plan: {}, Cycle: {}",
                userId, plan.getName(), cycle);

    }

    private List<String> findOtherRunningStripeSubscriptionIds(Subscription newSubscription) {
        if (newSubscription.getCustomer() == null) {
            return List.of();
        }
        try {
            return stripeService.listSubscriptions(newSubscription.getCustomer()).stream()
                    .filter(s -> STILL_RUNNING_STATUSES.contains(s.getStatus()))
                    .map(Subscription::getId)
                    .filter(id -> !id.equals(newSubscription.getId()))
                    .toList();
        } catch (StripeException e) {
            throw new IllegalStateException("Could not list Stripe subscriptions for customer "
                    + newSubscription.getCustomer() + "; the event will be retried", e);
        }
    }

    /**
     * Start free trial
     */
    @Transactional
    public UserSubscription startTrial(UUID userId, String planName, int trialDays) {
        logger.info("Starting trial - User: {}, Plan: {}, Days: {}", userId, planName, trialDays);
        SubscriptionPlan plan = planRepository.findByName(planName)
                .orElseThrow(() -> new ResourceNotFoundException("Plan"));

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
     * Cancel subscription.
     * Paid subscriptions are cancelled in Stripe at the end of the billing period, so the user
     * keeps access until endDate and is not charged again. If the Stripe call fails, nothing is
     * changed locally and the error is returned to the caller.
     */
    @Transactional
    public UserSubscription cancelSubscription(UUID userId) {
        logger.info("Cancelling subscription for user: {}", userId);

        UserSubscription subscription = getUserSubscription(userId)
                .orElseThrow(() -> {
                    logger.warn("No active subscription found for user: {}", userId);
                    return new ValidationException("No active subscription");
                });

        // Older rows never stored the Stripe subscription ID - look it up via the Stripe customer
        List<String> stripeSubscriptionIds = subscription.getStripeSubscriptionId() != null
                ? List.of(subscription.getStripeSubscriptionId())
                : findBillableStripeSubscriptionIds(userId, false);

        try {
            for (String stripeSubscriptionId : stripeSubscriptionIds) {
                stripeService.cancelSubscriptionAtPeriodEnd(stripeSubscriptionId);
                logger.info("Stripe subscription {} set to cancel at period end", stripeSubscriptionId);
            }
        } catch (StripeException e) {
            logger.error("Failed to cancel Stripe subscription for user: {}", userId, e);
            throw new BillingUnavailableException("Could not cancel your subscription. Please try again.", e);
        }

        if (stripeSubscriptionIds.isEmpty()) {
            // Nothing billed in Stripe (e.g. trial) - end access now
            subscription.setStatus(SubscriptionStatus.CANCELLED);
        } else if (subscription.getStripeSubscriptionId() == null) {
            subscription.setStripeSubscriptionId(stripeSubscriptionIds.get(0));
        }

        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setAutoRenew(false);

        UserSubscription saved = subscriptionRepository.save(subscription);
        logger.info("Subscription cancelled successfully for user: {}", userId);
        return saved;
    }

    /**
     * Cancel all of the user's Stripe subscriptions immediately, for account deletion.
     * Throws if Stripe can't be reached, so an account is never deleted while still being billed.
     */
    public void cancelAllForAccountDeletion(UUID userId) {
        String stripeCustomerId = userRepository.findById(userId)
                .map(User::getStripeCustomerId)
                .orElse(null);

        try {
            // Subscriptions under the customer, plus any recorded locally (covers rows whose
            // user never got a stripeCustomerId stored)
            Set<String> candidateIds = new LinkedHashSet<>();
            if (stripeCustomerId != null) {
                stripeService.listSubscriptions(stripeCustomerId).stream()
                        .filter(s -> STILL_RUNNING_STATUSES.contains(s.getStatus()))
                        .forEach(s -> candidateIds.add(s.getId()));
            }
            for (UserSubscription local : subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
                String id = local.getStripeSubscriptionId();
                if (id != null && !candidateIds.contains(id)
                        && STILL_RUNNING_STATUSES.contains(stripeService.retrieveSubscription(id).getStatus())) {
                    candidateIds.add(id);
                }
            }

            for (String stripeSubscriptionId : candidateIds) {
                stripeService.cancelSubscription(stripeSubscriptionId);
                logger.info("Cancelled Stripe subscription {} for deleted user {}", stripeSubscriptionId, userId);
            }

            // Remove the customer record (email, name, payment methods) from Stripe as well
            if (stripeCustomerId != null) {
                stripeService.deleteCustomer(stripeCustomerId);
                logger.info("Deleted Stripe customer {} for deleted user {}", stripeCustomerId, userId);
            }
        } catch (StripeException e) {
            logger.error("Failed to cancel Stripe billing for user being deleted: {}", userId, e);
            throw new BillingUnavailableException("Could not cancel your subscription, so your account was not deleted. "
                    + "Please try again.", e);
        }
    }

    // Every Stripe subscription status that isn't final (can still bill or resume)
    private static final Set<String> STILL_RUNNING_STATUSES =
            Set.of("active", "trialing", "past_due", "unpaid", "incomplete", "paused");

    /**
     * Stripe subscriptions for this user that are still running
     *
     * @param includeEndingAtPeriodEnd also include subscriptions already set to cancel at period end
     */
    private List<String> findBillableStripeSubscriptionIds(UUID userId, boolean includeEndingAtPeriodEnd) {
        String stripeCustomerId = userRepository.findById(userId)
                .map(User::getStripeCustomerId)
                .orElse(null);

        if (stripeCustomerId == null) {
            return List.of();
        }

        try {
            return stripeService.listSubscriptions(stripeCustomerId).stream()
                    .filter(s -> List.of("active", "trialing", "past_due").contains(s.getStatus()))
                    .filter(s -> includeEndingAtPeriodEnd || !Boolean.TRUE.equals(s.getCancelAtPeriodEnd()))
                    .map(Subscription::getId)
                    .toList();
        } catch (StripeException e) {
            logger.error("Failed to list Stripe subscriptions for customer: {}", stripeCustomerId, e);
            throw new BillingUnavailableException("Could not cancel your subscription. Please try again.", e);
        }
    }

    // ============================================
    // STRIPE WEBHOOK HANDLING
    // ============================================

    /**
     * Forget processed webhook events after 30 days. Stripe stops retrying an event after 3 days.
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void deleteOldProcessedEvents() {
        int deleted = processedEventRepository.deleteByProcessedAtBefore(LocalDateTime.now().minusDays(30));
        if (deleted > 0) {
            logger.info("Deleted {} processed Stripe events older than 30 days", deleted);
        }
    }

    /**
     * Handle Stripe webhook events.
     *
     * Stripe is the source of truth for paid subscriptions: for every subscription-related event we
     * fetch the current subscription from Stripe and copy its state (plan, status, period end) onto
     * the local row. That makes handling idempotent and independent of event order or retries.
     * Exceptions propagate so the controller returns an error and Stripe retries the event.
     */
    @Transactional
    public void handleStripeWebhook(String payload, String sigHeader) {
        logger.info("Processing Stripe webhook");

        Event event;

        try {
            // Verify webhook signature
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
            logger.debug("Webhook event constructed - Type: {}, ID: {}",
                    event.getType(), event.getId());
        } catch (SignatureVerificationException e) {
            logger.error("Invalid webhook signature", e);
            throw new IllegalArgumentException("Invalid webhook signature");
        }

        logger.info("Handling Stripe webhook event: {} ({})", event.getType(), event.getId());

        // Stripe can deliver an event more than once; handle each event ID once. This is part
        // of this transaction, so if handling fails below, the event isn't marked and Stripe's
        // retry is processed
        if (processedEventRepository.markProcessed(event.getId(), event.getType()) == 0) {
            logger.info("Stripe event {} already processed, skipping", event.getId());
            return;
        }

        try {
            switch (event.getType()) {
                case "checkout.session.completed" ->
                        handleCheckoutCompleted((Session) getEventObject(event));

                case "customer.subscription.created",
                     "customer.subscription.updated",
                     "customer.subscription.deleted" ->
                        syncSubscriptionFromStripe(((Subscription) getEventObject(event)).getId());

                // Renewals extend the period; failed payments move the subscription to past_due
                case "invoice.payment_succeeded", "invoice.payment_failed" -> {
                    String stripeSubscriptionId = getInvoiceSubscriptionId((Invoice) getEventObject(event));
                    if (stripeSubscriptionId != null) {
                        syncSubscriptionFromStripe(stripeSubscriptionId);
                    }
                }

                default -> logger.debug("Unhandled webhook event type: {}", event.getType());
            }
        } catch (StripeException e) {
            throw new RuntimeException("Stripe API error while handling event " + event.getId(), e);
        }
    }

    /**
     * Handle checkout.session.completed
     * This is called when user completes payment
     */
    private void handleCheckoutCompleted(Session session) throws StripeException {
        logger.debug("Checkout session - ID: {}, Customer: {}, Subscription: {}",
                session.getId(), session.getCustomer(), session.getSubscription());

        if (session.getSubscription() == null) {
            logger.warn("No subscription in session {}, skipping", session.getId());
            return;
        }

        Subscription stripeSubscription = stripeService.retrieveSubscription(session.getSubscription());

        // Prefer the user ID we attached to the session; fall back to the Stripe customer
        UUID userId = session.getClientReferenceId() != null
                ? UUID.fromString(session.getClientReferenceId())
                : findUserByStripeCustomerId(session.getCustomer());

        if (userId == null) {
            throw new IllegalStateException("User not found for Stripe customer: " + session.getCustomer());
        }

        upsertSubscription(userId, stripeSubscription);
        logger.info("Checkout completed successfully processed - Session: {}", session.getId());
    }

    /**
     * Copy the current state of a Stripe subscription onto the local row (creating it if needed)
     */
    private void syncSubscriptionFromStripe(String stripeSubscriptionId) throws StripeException {
        Subscription stripeSubscription = stripeService.retrieveSubscription(stripeSubscriptionId);

        UUID userId = subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
                .map(UserSubscription::getUserId)
                .orElseGet(() -> findUserByStripeCustomerId(stripeSubscription.getCustomer()));

        if (userId == null) {
            logger.warn("No user for Stripe subscription {} (customer {}), skipping",
                    stripeSubscriptionId, stripeSubscription.getCustomer());
            return;
        }

        upsertSubscription(userId, stripeSubscription);
    }

    private void upsertSubscription(UUID userId, Subscription stripeSubscription) {
        SubscriptionPlan plan = findPlanByStripePriceId(getPriceId(stripeSubscription));
        BillingCycle billingCycle = getBillingCycle(stripeSubscription);

        Optional<UserSubscription> existing =
                subscriptionRepository.findByStripeSubscriptionId(stripeSubscription.getId());

        if (existing.isEmpty()) {
            if (mapStripeStatus(stripeSubscription.getStatus()) != SubscriptionStatus.ACTIVE) {
                logger.info("Ignoring inactive Stripe subscription {} with no local record ({})",
                        stripeSubscription.getId(), stripeSubscription.getStatus());
                return;
            }
            createSubscription(userId, plan, billingCycle, stripeSubscription);
            return;
        }

        UserSubscription subscription = existing.get();
        SubscriptionStatus status = mapStripeStatus(stripeSubscription.getStatus());

        subscription.setPlan(plan);
        subscription.setBillingCycle(billingCycle);
        subscription.setStatus(status);
        subscription.setEndDate(getCurrentPeriodEnd(stripeSubscription, billingCycle));
        subscription.setAutoRenew(!Boolean.TRUE.equals(stripeSubscription.getCancelAtPeriodEnd()));
        subscription.setUpdatedAt(LocalDateTime.now());

        if (status == SubscriptionStatus.CANCELLED && subscription.getCancelledAt() == null) {
            subscription.setCancelledAt(LocalDateTime.now());
        }

        subscriptionRepository.save(subscription);
        logger.info("Synced Stripe subscription {} - User: {}, Plan: {}, Status: {}, Ends: {}",
                stripeSubscription.getId(), userId, plan.getName(), status, subscription.getEndDate());
    }

    /**
     * Map Stripe's subscription status to ours.
     * past_due keeps access while Stripe retries the payment; once Stripe gives up the
     * subscription becomes canceled or unpaid and access ends.
     */
    private SubscriptionStatus mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "active", "trialing", "past_due" -> SubscriptionStatus.ACTIVE;
            case "canceled", "incomplete_expired" -> SubscriptionStatus.CANCELLED;
            default -> SubscriptionStatus.SUSPENDED; // unpaid, incomplete, paused
        };
    }

    private String getPriceId(Subscription stripeSubscription) {
        return stripeSubscription.getItems().getData().get(0).getPrice().getId();
    }

    private BillingCycle getBillingCycle(Subscription stripeSubscription) {
        return "year".equals(stripeSubscription.getItems().getData().get(0)
                .getPrice().getRecurring().getInterval())
                ? BillingCycle.YEARLY
                : BillingCycle.MONTHLY;
    }

    private String getInvoiceSubscriptionId(Invoice invoice) {
        if (invoice.getParent() == null || invoice.getParent().getSubscriptionDetails() == null) {
            return null; // one-off invoice, not tied to a subscription
        }
        return invoice.getParent().getSubscriptionDetails().getSubscription();
    }

    /**
     * Deserialize the event payload. Falls back to unsafe deserialization when the webhook
     * endpoint's API version differs from the SDK's, which would otherwise yield nothing.
     */
    private StripeObject getEventObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        return deserializer.getObject().orElseGet(() -> {
            try {
                return deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                throw new IllegalStateException("Could not deserialize Stripe event " + event.getId(), e);
            }
        });
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    /**
     * End of the current billing period, from Stripe's subscription item
     */
    private LocalDateTime getCurrentPeriodEnd(Subscription stripeSubscription, BillingCycle cycle) {
        Long periodEnd = stripeSubscription.getItems().getData().get(0).getCurrentPeriodEnd();

        if (periodEnd == null) {
            return cycle == BillingCycle.YEARLY
                    ? LocalDateTime.now().plusYears(1)
                    : LocalDateTime.now().plusMonths(1);
        }

        return LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneId.systemDefault());
    }

    private SubscriptionPlan getFreePlan() {
        return planRepository.findByName(PlanTier.FREE.name())
                .orElseThrow(() -> new RuntimeException("FREE plan not found"));
    }



    // Where each countable feature's limit lives in subscription_plans.limits (JSON; -1 = unlimited).
    // Keys must match feature_flags.feature_name on the left, the stored JSON on the right
    private static final Map<String, String> LIMIT_KEYS = Map.of(
            "ai_coach", "ai_messages_per_month",
            "budget_create", "max_budgets",
            "bank_account", "bank_accounts",
            "export_reports", "exports_per_month");

    private static final ObjectMapper LIMITS_JSON = new ObjectMapper();

    /**
     * The plan's limit for a feature, from its limits JSON so it can be changed in the database
     * without a deploy. A missing key or unreadable JSON falls back to the built-in limit, so a
     * mistake there never makes a feature unlimited. -1 means unlimited.
     */
    private Integer getFeatureLimit(SubscriptionPlan plan, String featureName) {
        String key = LIMIT_KEYS.get(featureName);
        if (key == null) {
            return -1; // Not a counted feature
        }
        if (plan.getLimits() != null) {
            try {
                JsonNode value = LIMITS_JSON.readTree(plan.getLimits()).get(key);
                if (value != null && value.canConvertToInt()) {
                    return value.asInt();
                }
            } catch (JsonProcessingException e) {
                logger.warn("Unreadable limits JSON for plan {}, using built-in limits", plan.getName());
            }
        }
        return builtInLimit(plan, featureName);
    }

    private static int builtInLimit(SubscriptionPlan plan, String featureName) {
        return switch (featureName) {
            case "ai_coach" -> plan.isFree() ? 5 : -1;
            case "budget_create" -> plan.isFree() ? 3 : -1;
            case "bank_account" -> plan.isFree() ? 1 : (plan.isPremium() ? 5 : -1);
            case "export_reports" -> plan.isFree() ? 0 : -1;
            default -> -1;
        };
    }

    // ============================================
    // HELPER METHODS FOR WEBHOOK HANDLING
    // ============================================

    /**
     * Find plan by Stripe price ID
     */
    private SubscriptionPlan findPlanByStripePriceId(String stripePriceId) {
        logger.debug("Finding plan by Stripe price ID: {}", stripePriceId);

        return planRepository.findByStripePriceId(stripePriceId)
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
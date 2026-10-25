package com.financecoach.backend.service;

import com.financecoach.backend.exception.ResourceNotFoundException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.exception.BillingUnavailableException;
import com.financecoach.backend.exception.UsageLimitExceededException;
import com.financecoach.backend.model.BillingCycle;
import com.financecoach.backend.model.FeatureFlag;
import com.financecoach.backend.model.SubscriptionPlan;
import com.financecoach.backend.model.SubscriptionStatus;
import com.financecoach.backend.model.UsageTracking;
import com.financecoach.backend.model.User;
import com.financecoach.backend.model.UserSubscription;
import com.financecoach.backend.repository.FeatureFlagRepository;
import com.financecoach.backend.repository.SubscriptionPlanRepository;
import com.financecoach.backend.repository.UsageTrackingRepository;
import com.financecoach.backend.repository.UserRepository;
import com.financecoach.backend.repository.UserSubscriptionRepository;
import com.stripe.Stripe;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Customer;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private UserSubscriptionRepository subscriptionRepository;

    @Mock
    private SubscriptionPlanRepository planRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StripeService stripeService;

    @Mock
    private FeatureFlagRepository featureFlagRepository;

    @Mock
    private UsageTrackingRepository usageRepository;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private UUID userId;

    private static final String WEBHOOK_SECRET = "whsec_test";
    private static final String MONTHLY_PRICE = "price_premium_monthly";

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        ReflectionTestUtils.setField(subscriptionService, "webhookSecret", WEBHOOK_SECRET);
    }

    // --- cancelSubscription ---

    @Test
    void cancelSubscription_cancelsInStripeAtPeriodEnd_andKeepsAccessUntilEndDate() throws Exception {
        UserSubscription sub = activeSubscription("sub_123");
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserSubscription result = subscriptionService.cancelSubscription(userId);

        verify(stripeService).cancelSubscriptionAtPeriodEnd("sub_123");
        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.getAutoRenew()).isFalse();
        assertThat(result.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelSubscription_withoutStoredId_findsSubscriptionViaStripeCustomer() throws Exception {
        UserSubscription sub = activeSubscription(null);
        User user = new User();
        user.setStripeCustomerId("cus_1");
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(sub));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.listSubscriptions("cus_1")).thenReturn(List.of(
                stripeSubscription("sub_live", "active", false),
                stripeSubscription("sub_old", "canceled", false),
                stripeSubscription("sub_ending", "active", true)
        ));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserSubscription result = subscriptionService.cancelSubscription(userId);

        verify(stripeService).cancelSubscriptionAtPeriodEnd("sub_live");
        verify(stripeService, never()).cancelSubscriptionAtPeriodEnd("sub_old");
        verify(stripeService, never()).cancelSubscriptionAtPeriodEnd("sub_ending");
        assertThat(result.getStripeSubscriptionId()).isEqualTo("sub_live");
        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void cancelSubscription_withNothingBilledInStripe_endsAccessNow() {
        UserSubscription sub = activeSubscription(null);
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(sub));
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserSubscription result = subscriptionService.cancelSubscription(userId);

        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(result.getAutoRenew()).isFalse();
    }

    @Test
    void cancelSubscription_whenStripeFails_throwsAndDoesNotSave() throws Exception {
        UserSubscription sub = activeSubscription("sub_123");
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(sub));
        when(stripeService.cancelSubscriptionAtPeriodEnd(anyString()))
                .thenThrow(new ApiConnectionException("network down"));

        assertThatThrownBy(() -> subscriptionService.cancelSubscription(userId))
                .isInstanceOf(BillingUnavailableException.class)
                .hasMessageContaining("Could not cancel");

        verify(subscriptionRepository, never()).save(any());
        assertThat(sub.getAutoRenew()).isTrue();
    }

    @Test
    void cancelSubscription_withNoActiveSubscription_throws() {
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.cancelSubscription(userId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No active subscription");
    }

    @Test
    void cancelAllForAccountDeletion_cancelsImmediately_includingSubscriptionsEndingAtPeriodEnd() throws Exception {
        User user = new User();
        user.setStripeCustomerId("cus_1");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.listSubscriptions("cus_1")).thenReturn(List.of(
                stripeSubscription("sub_live", "active", false),
                stripeSubscription("sub_ending", "active", true),
                stripeSubscription("sub_old", "canceled", false)));

        subscriptionService.cancelAllForAccountDeletion(userId);

        verify(stripeService).cancelSubscription("sub_live");
        verify(stripeService).cancelSubscription("sub_ending");
        verify(stripeService, never()).cancelSubscription("sub_old");
        verify(stripeService).deleteCustomer("cus_1");
    }

    @Test
    void cancelAllForAccountDeletion_alsoCancelsLocallyRecordedSubscriptions_andUnpaidOnes() throws Exception {
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User())); // no Stripe customer stored
        when(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(activeSubscription("sub_local"), activeSubscription("sub_done")));
        when(stripeService.retrieveSubscription("sub_local")).thenReturn(stripeSubscription("sub_local", "unpaid", false));
        when(stripeService.retrieveSubscription("sub_done")).thenReturn(stripeSubscription("sub_done", "canceled", false));

        subscriptionService.cancelAllForAccountDeletion(userId);

        verify(stripeService).cancelSubscription("sub_local");
        verify(stripeService, never()).cancelSubscription("sub_done");
        verify(stripeService, never()).deleteCustomer(anyString());
    }

    @Test
    void cancelAllForAccountDeletion_whenStripeFails_throws() throws Exception {
        User user = new User();
        user.setStripeCustomerId("cus_1");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.listSubscriptions("cus_1")).thenReturn(List.of(stripeSubscription("sub_live", "active", false)));
        when(stripeService.cancelSubscription("sub_live")).thenThrow(new ApiConnectionException("down"));

        assertThatThrownBy(() -> subscriptionService.cancelAllForAccountDeletion(userId))
                .hasMessageContaining("not deleted");
    }

    // --- createSubscription ---

    @Test
    void createSubscription_storesStripeIdAndPeriodEnd() {
        long periodEnd = Instant.parse("2026-11-16T00:00:00Z").getEpochSecond();
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());

        subscriptionService.createSubscription(userId, plan("PREMIUM"), BillingCycle.MONTHLY,
                stripeSubscription("sub_new", "active", false, periodEnd));

        ArgumentCaptor<UserSubscription> saved = ArgumentCaptor.forClass(UserSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getStripeSubscriptionId()).isEqualTo("sub_new");
        assertThat(saved.getValue().getEndDate()).isEqualTo(
                LocalDateTime.ofInstant(Instant.ofEpochSecond(periodEnd), ZoneId.systemDefault()));
        assertThat(saved.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void createSubscription_whenWebhookIsRetried_doesNotCancelOrDuplicate() throws Exception {
        when(subscriptionRepository.findActiveByUserId(userId))
                .thenReturn(Optional.of(activeSubscription("sub_new")));

        subscriptionService.createSubscription(userId, plan("PREMIUM"), BillingCycle.MONTHLY,
                stripeSubscription("sub_new", "active", false));

        verify(stripeService, never()).cancelSubscription(anyString());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void createSubscription_locksTheUserBeforeCheckingTheCurrentSubscription() {
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());

        subscriptionService.createSubscription(userId, plan("PREMIUM"), BillingCycle.MONTHLY,
                stripeSubscription("sub_new", "active", false));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(userRepository, subscriptionRepository);
        order.verify(userRepository).lockById(userId);
        order.verify(subscriptionRepository).findActiveByUserId(userId);
    }

    @Test
    void createSubscription_secondEventForTheSameSubscription_afterWaitingOnTheLock_skips() throws Exception {
        // The first event recorded sub_new while this one waited for the lock
        when(subscriptionRepository.findActiveByUserId(userId))
                .thenReturn(Optional.of(activeSubscription("sub_new")));

        subscriptionService.createSubscription(userId, plan("PRO"), BillingCycle.MONTHLY,
                stripeSubscription("sub_new", "active", false));

        verify(userRepository).lockById(userId);
        verify(stripeService, never()).cancelSubscription(anyString());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void createSubscription_onUpgrade_cancelsPreviousStripeSubscription() throws Exception {
        UserSubscription previous = activeSubscription("sub_old");
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(previous));

        subscriptionService.createSubscription(userId, plan("PRO"), BillingCycle.MONTHLY,
                stripeSubscription("sub_new", "active", false));

        verify(stripeService).cancelSubscription("sub_old");
        assertThat(previous.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void createSubscription_onUpgradeFromARowWithoutAStripeId_cancelsTheCustomersOtherRunningSubscriptions()
            throws Exception {
        // Older rows never stored the Stripe ID, but Stripe still bills that subscription
        UserSubscription previous = activeSubscription(null);
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(previous));
        Subscription upgrade = stripeSubscription("sub_new", "active", false);
        upgrade.setCustomer("cus_1");
        when(stripeService.listSubscriptions("cus_1")).thenReturn(List.of(
                stripeSubscription("sub_new", "active", false),
                stripeSubscription("sub_legacy", "active", false),
                stripeSubscription("sub_dead", "canceled", false)
        ));

        subscriptionService.createSubscription(userId, plan("PRO"), BillingCycle.MONTHLY, upgrade);

        verify(stripeService).cancelSubscription("sub_legacy");
        verify(stripeService, never()).cancelSubscription("sub_new");
        verify(stripeService, never()).cancelSubscription("sub_dead");
        assertThat(previous.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    // --- feature limits (names must match the seeded feature_flags) ---

    @Test
    void canUseFeature_aiCoach_freePlanAllowsFiveMessagesPerMonth() {
        stubFlag("ai_coach", "FREE");
        stubCurrentPlan("FREE");
        stubUsage("ai_coach", 4);
        assertThat(subscriptionService.canUseFeature(userId, "ai_coach")).isTrue();

        stubUsage("ai_coach", 5);
        assertThat(subscriptionService.canUseFeature(userId, "ai_coach")).isFalse();
    }

    @Test
    void canUseFeature_aiCoach_paidPlanIsUnlimited() {
        stubFlag("ai_coach", "FREE");
        stubCurrentPlan("PREMIUM");

        assertThat(subscriptionService.canUseFeature(userId, "ai_coach")).isTrue();
    }

    @Test
    void hasFeatureAccess_exportReports_requiresPremium() {
        stubFlag("export_reports", "PREMIUM");

        stubCurrentPlan("FREE");
        assertThat(subscriptionService.hasFeatureAccess(userId, "export_reports")).isFalse();

        stubCurrentPlan("PRO");
        assertThat(subscriptionService.hasFeatureAccess(userId, "export_reports")).isTrue();
    }

    // --- handleStripeWebhook ---

    @Test
    void webhook_invoicePaid_extendsEndDateToNewPeriod() throws Exception {
        long newPeriodEnd = Instant.parse("2026-12-16T00:00:00Z").getEpochSecond();
        UserSubscription local = activeSubscription("sub_1");
        local.setEndDate(LocalDateTime.now().plusDays(1));
        stubPlans();
        when(stripeService.retrieveSubscription("sub_1"))
                .thenReturn(stripeSubscription("sub_1", "active", false, newPeriodEnd));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(local));

        sendWebhook("invoice.payment_succeeded", """
                {"id": "in_1", "object": "invoice",
                 "parent": {"type": "subscription_details", "subscription_details": {"subscription": "sub_1"}}}
                """);

        verify(subscriptionRepository).save(local);
        assertThat(local.getEndDate()).isEqualTo(
                LocalDateTime.ofInstant(Instant.ofEpochSecond(newPeriodEnd), ZoneId.systemDefault()));
        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void webhook_subscriptionDeleted_endsAccess() throws Exception {
        UserSubscription local = activeSubscription("sub_1");
        stubPlans();
        when(stripeService.retrieveSubscription("sub_1"))
                .thenReturn(stripeSubscription("sub_1", "canceled", false));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(local));

        sendWebhook("customer.subscription.deleted", "{\"id\": \"sub_1\", \"object\": \"subscription\"}");

        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(local.getCancelledAt()).isNotNull();
    }

    @Test
    void webhook_cancelAtPeriodEnd_keepsAccessButStopsRenewal() throws Exception {
        UserSubscription local = activeSubscription("sub_1");
        stubPlans();
        when(stripeService.retrieveSubscription("sub_1"))
                .thenReturn(stripeSubscription("sub_1", "active", true));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(local));

        sendWebhook("customer.subscription.updated", "{\"id\": \"sub_1\", \"object\": \"subscription\"}");

        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(local.getAutoRenew()).isFalse();
    }

    @Test
    void webhook_paymentFailed_keepsAccessWhileStripeRetries() throws Exception {
        UserSubscription local = activeSubscription("sub_1");
        stubPlans();
        when(stripeService.retrieveSubscription("sub_1"))
                .thenReturn(stripeSubscription("sub_1", "past_due", false));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(local));

        sendWebhook("invoice.payment_failed", """
                {"id": "in_2", "object": "invoice",
                 "parent": {"type": "subscription_details", "subscription_details": {"subscription": "sub_1"}}}
                """);

        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void webhook_checkoutCompleted_createsSubscriptionForClientReferenceUser() throws Exception {
        stubPlans();
        when(stripeService.retrieveSubscription("sub_new"))
                .thenReturn(stripeSubscription("sub_new", "active", false));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_new")).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());

        sendWebhook("checkout.session.completed", """
                {"id": "cs_1", "object": "checkout.session", "customer": "cus_1",
                 "subscription": "sub_new", "client_reference_id": "%s"}
                """.formatted(userId));

        ArgumentCaptor<UserSubscription> saved = ArgumentCaptor.forClass(UserSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getStripeSubscriptionId()).isEqualTo("sub_new");
        assertThat(saved.getValue().getPlan().getName()).isEqualTo("PREMIUM");
    }

    @Test
    void webhook_withOlderApiVersion_isStillProcessed() throws Exception {
        UserSubscription local = activeSubscription("sub_1");
        stubPlans();
        when(stripeService.retrieveSubscription("sub_1"))
                .thenReturn(stripeSubscription("sub_1", "canceled", false));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(local));

        sendWebhook("customer.subscription.deleted", "{\"id\": \"sub_1\", \"object\": \"subscription\"}",
                "2024-06-20");

        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void webhook_withInvalidSignature_isRejected() {
        String payload = eventJson("customer.subscription.updated", "{\"id\": \"sub_1\"}", Stripe.API_VERSION);

        assertThatThrownBy(() -> subscriptionService.handleStripeWebhook(payload, "t=1,v1=bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webhook_whenStripeApiFails_throwsSoStripeRetries() throws Exception {
        when(stripeService.retrieveSubscription("sub_1")).thenThrow(new ApiConnectionException("down"));

        assertThatThrownBy(() -> sendWebhook("customer.subscription.updated",
                "{\"id\": \"sub_1\", \"object\": \"subscription\"}"))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    // --- plans ---

    @Test
    void planLookups_delegateToTheRepository() {
        SubscriptionPlan premium = plan("PREMIUM");
        UUID planId = UUID.randomUUID();
        when(planRepository.findByIsActiveTrue()).thenReturn(List.of(premium));
        when(planRepository.findById(planId)).thenReturn(Optional.of(premium));
        when(planRepository.findByName("PREMIUM")).thenReturn(Optional.of(premium));

        assertThat(subscriptionService.getAllPlans()).containsExactly(premium);
        assertThat(subscriptionService.getPlanById(planId)).contains(premium);
        assertThat(subscriptionService.getPlanByName("PREMIUM")).contains(premium);
    }

    @Test
    void getUserPlan_usesTheActiveSubscription_orFallsBackToFree() {
        UserSubscription pro = activeSubscription("sub_1");
        pro.setPlan(plan("PRO"));
        UUID freeUser = UUID.randomUUID();
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(pro));
        when(subscriptionRepository.findActiveByUserId(freeUser)).thenReturn(Optional.empty());
        when(planRepository.findByName("FREE")).thenReturn(Optional.of(plan("FREE")));

        assertThat(subscriptionService.getUserPlan(userId).getName()).isEqualTo("PRO");
        assertThat(subscriptionService.getUserPlan(freeUser).getName()).isEqualTo("FREE");
    }

    @Test
    void getUserPlan_withoutAFreePlanSeeded_failsClearly() {
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());
        when(planRepository.findByName("FREE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.getUserPlan(userId)).hasMessageContaining("FREE plan not found");
    }

    // --- feature access and usage ---

    @Test
    void hasFeatureAccess_isDeniedForMissingOrDisabledFlags_andOpenWhenNoPlanIsRequired() {
        FeatureFlag disabled = new FeatureFlag();
        disabled.setIsEnabled(false);
        FeatureFlag open = new FeatureFlag();
        open.setIsEnabled(true);
        open.setRequiredPlan(null);
        when(featureFlagRepository.findByFeatureName("missing")).thenReturn(Optional.empty());
        when(featureFlagRepository.findByFeatureName("disabled")).thenReturn(Optional.of(disabled));
        when(featureFlagRepository.findByFeatureName("open")).thenReturn(Optional.of(open));

        assertThat(subscriptionService.hasFeatureAccess(userId, "missing")).isFalse();
        assertThat(subscriptionService.hasFeatureAccess(userId, "disabled")).isFalse();
        assertThat(subscriptionService.hasFeatureAccess(userId, "open")).isTrue();
        assertThat(subscriptionService.canUseFeature(userId, "missing")).isFalse();
    }

    @Test
    void canUseFeature_withoutALimit_isUnlimited() {
        stubFlag("advanced_analytics", "PREMIUM");
        stubCurrentPlan("PREMIUM");

        assertThat(subscriptionService.canUseFeature(userId, "advanced_analytics")).isTrue();
    }

    @Test
    void getLimit_returnsThePlansLimit_orMinusOneWhenUnlimited() {
        stubCurrentPlan("FREE");
        assertThat(subscriptionService.getLimit(userId, "budget_create")).isEqualTo(3);
        assertThat(subscriptionService.getLimit(userId, "bank_account")).isEqualTo(1);
        assertThat(subscriptionService.getLimit(userId, "anything_else")).isEqualTo(-1);
    }

    @Test
    void reserveUsage_countsAtomicallyAgainstThePlansMonthlyLimit() {
        stubCurrentPlan("FREE");
        LocalDate today = LocalDate.now();
        when(usageRepository.reserveUsage(userId, "ai_coach", today.withDayOfMonth(1),
                today.withDayOfMonth(today.lengthOfMonth()), 5)).thenReturn(1).thenReturn(0);

        subscriptionService.reserveUsage(userId, "ai_coach");
        assertThatThrownBy(() -> subscriptionService.reserveUsage(userId, "ai_coach"))
                .isInstanceOf(UsageLimitExceededException.class);
    }

    @Test
    void reserveUsage_onUnlimitedPlans_stillCounts_butNeverRefuses() {
        stubCurrentPlan("PREMIUM");
        when(usageRepository.reserveUsage(eq(userId), eq("ai_coach"), any(), any(), eq(Integer.MAX_VALUE))).thenReturn(1);

        subscriptionService.reserveUsage(userId, "ai_coach");

        verify(usageRepository).reserveUsage(eq(userId), eq("ai_coach"), any(), any(), eq(Integer.MAX_VALUE));
    }

    @Test
    void reserveUsage_withAZeroLimit_isRefusedWithoutTouchingTheCounter() {
        stubCurrentPlan("FREE");

        assertThatThrownBy(() -> subscriptionService.reserveUsage(userId, "export_reports"))
                .isInstanceOf(UsageLimitExceededException.class);
        verify(usageRepository, never()).reserveUsage(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void releaseUsage_givesBackThisMonthsUse() {
        subscriptionService.releaseUsage(userId, "ai_coach");

        verify(usageRepository).releaseUsage(userId, "ai_coach", LocalDate.now().withDayOfMonth(1));
    }

    @Test
    void getRemainingUsage_countsDownToZero_andIsMinusOneWhenUnlimited() {
        stubCurrentPlan("FREE");
        when(usageRepository.findByUserIdAndFeatureNameAndPeriod(any(), any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(usage(7)));

        assertThat(subscriptionService.getRemainingUsage(userId, "ai_coach")).isEqualTo(5);
        assertThat(subscriptionService.getRemainingUsage(userId, "ai_coach")).isZero();
        assertThat(subscriptionService.getRemainingUsage(userId, "something_unlimited")).isEqualTo(-1);
    }

    @Test
    void featureLimits_dependOnThePlan() {
        stubCurrentPlan("PREMIUM");
        when(usageRepository.findByUserIdAndFeatureNameAndPeriod(any(), any(), any(), any()))
                .thenReturn(Optional.of(usage(1)));

        assertThat(subscriptionService.getRemainingUsage(userId, "bank_account")).isEqualTo(4);
        assertThat(subscriptionService.getRemainingUsage(userId, "budget_create")).isEqualTo(-1);
        assertThat(subscriptionService.getRemainingUsage(userId, "export_reports")).isEqualTo(-1);
    }

    // --- checkout ---

    @Test
    void createCheckoutSession_reusesTheStripeCustomer_andUsesTheCyclesPrice() throws Exception {
        SubscriptionPlan premium = plan("PREMIUM");
        premium.setStripePriceIdMonthly("price_m");
        premium.setStripePriceIdYearly("price_y");
        UUID planId = UUID.randomUUID();
        User user = new User();
        user.setStripeCustomerId("cus_existing");
        ReflectionTestUtils.setField(subscriptionService, "frontendUrl", "https://app.test");
        when(planRepository.findById(planId)).thenReturn(Optional.of(premium));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        com.stripe.model.checkout.Session session = new com.stripe.model.checkout.Session();
        session.setUrl("https://checkout.stripe.test/session");
        when(stripeService.createCheckoutSession(any(), any(), any(), any(), any())).thenReturn(session);

        String url = subscriptionService.createCheckoutSession(userId, planId, BillingCycle.YEARLY);

        assertThat(url).isEqualTo("https://checkout.stripe.test/session");
        verify(stripeService).createCheckoutSession(eq("cus_existing"), eq("price_y"),
                eq("https://app.test/subscription/success?session_id={CHECKOUT_SESSION_ID}"),
                eq("https://app.test/subscription/cancel"), eq(userId.toString()));
        verify(stripeService, never()).createCustomer(anyString(), anyString());
    }

    @Test
    void createCheckoutSession_createsAStripeCustomerTheFirstTime() throws Exception {
        SubscriptionPlan premium = plan("PREMIUM");
        premium.setStripePriceIdMonthly("price_m");
        UUID planId = UUID.randomUUID();
        User user = new User();
        user.setEmail("jane@example.com");
        Customer customer = new Customer();
        customer.setId("cus_new");
        when(planRepository.findById(planId)).thenReturn(Optional.of(premium));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(stripeService.createCustomer("jane@example.com", "jane@example.com")).thenReturn(customer);
        when(stripeService.createCheckoutSession(any(), any(), any(), any(), any()))
                .thenReturn(new com.stripe.model.checkout.Session());

        subscriptionService.createCheckoutSession(userId, planId, BillingCycle.MONTHLY);

        assertThat(user.getStripeCustomerId()).isEqualTo("cus_new");
        verify(userRepository).save(user);
        verify(stripeService).createCheckoutSession(eq("cus_new"), eq("price_m"), any(), any(), any());
    }

    @Test
    void createCheckoutSession_failsForUnknownPlans_users_orMissingPriceIds() throws Exception {
        UUID unknownPlan = UUID.randomUUID();
        UUID noPricePlan = UUID.randomUUID();
        UUID pricedPlan = UUID.randomUUID();
        SubscriptionPlan priced = plan("PRO");
        priced.setStripePriceIdMonthly("price_m");
        User user = new User();
        user.setStripeCustomerId("cus_1");
        when(planRepository.findById(unknownPlan)).thenReturn(Optional.empty());
        when(planRepository.findById(noPricePlan)).thenReturn(Optional.of(plan("PRO")));
        when(planRepository.findById(pricedPlan)).thenReturn(Optional.of(priced));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UUID ghost = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.createCheckoutSession(userId, unknownPlan, BillingCycle.MONTHLY))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Plan not found");
        assertThatThrownBy(() -> subscriptionService.createCheckoutSession(userId, noPricePlan, BillingCycle.MONTHLY))
                .hasMessageContaining("price ID not configured");
        assertThatThrownBy(() -> subscriptionService.createCheckoutSession(ghost, pricedPlan, BillingCycle.MONTHLY))
                .hasMessageContaining("User not found");
    }

    @Test
    void startTrial_createsATrialEndingAfterTheGivenDays() {
        when(planRepository.findByName("PREMIUM")).thenReturn(Optional.of(plan("PREMIUM")));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserSubscription trial = subscriptionService.startTrial(userId, "PREMIUM", 14);

        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(trial.getTrialEndDate()).isAfter(LocalDateTime.now().plusDays(13));
        assertThatThrownBy(() -> subscriptionService.startTrial(userId, "UNKNOWN", 14))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Plan not found");
    }

    @Test
    void createSubscription_onUpgrade_ifTheOldOneCantBeCancelled_failsWithoutCreatingTheNewOne() throws Exception {
        // Carrying on would leave both plans billing; failing makes Stripe retry the event
        UserSubscription previous = activeSubscription("sub_old");
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(previous));
        when(stripeService.cancelSubscription("sub_old")).thenThrow(new ApiConnectionException("down"));

        assertThatThrownBy(() -> subscriptionService.createSubscription(userId, plan("PRO"), BillingCycle.YEARLY,
                stripeSubscription("sub_new", "active", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sub_old");

        verify(subscriptionRepository, never()).save(any());
        assertThat(previous.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void webhook_upgradeWhoseOldSubscriptionCantBeCancelled_failsSoStripeRetries() throws Exception {
        User user = new User();
        user.setId(userId);
        stubPlans();
        when(stripeService.retrieveSubscription("sub_new")).thenReturn(stripeSubscription("sub_new", "active", false));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_new")).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(activeSubscription("sub_old")));
        when(stripeService.cancelSubscription("sub_old")).thenThrow(new ApiConnectionException("down"));

        assertThatThrownBy(() -> sendWebhook("checkout.session.completed", """
                {"id": "cs_1", "object": "checkout.session", "customer": "cus_1",
                 "subscription": "sub_new", "client_reference_id": "%s"}
                """.formatted(userId)))
                .isNotInstanceOf(IllegalArgumentException.class); // not a bad signature: controller returns 500
        verify(subscriptionRepository, never()).save(any());
    }

    // --- webhook edge cases ---

    @Test
    void webhook_checkoutWithoutASubscription_isIgnored() throws Exception {
        sendWebhook("checkout.session.completed", "{\"id\": \"cs_1\", \"object\": \"checkout.session\", \"mode\": \"payment\"}");

        verify(stripeService, never()).retrieveSubscription(anyString());
    }

    @Test
    void webhook_checkoutWithoutClientReference_findsTheUserByStripeCustomer() throws Exception {
        User user = new User();
        user.setId(userId);
        stubPlans();
        when(stripeService.retrieveSubscription("sub_new")).thenReturn(stripeSubscription("sub_new", "active", false));
        when(userRepository.findByStripeCustomerId("cus_1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_new")).thenReturn(Optional.empty());
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());

        sendWebhook("checkout.session.completed", """
                {"id": "cs_1", "object": "checkout.session", "customer": "cus_1", "subscription": "sub_new"}
                """);

        ArgumentCaptor<UserSubscription> saved = ArgumentCaptor.forClass(UserSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void webhook_checkoutForAnUnknownCustomer_failsSoStripeRetries() throws Exception {
        when(stripeService.retrieveSubscription("sub_new")).thenReturn(stripeSubscription("sub_new", "active", false));
        when(userRepository.findByStripeCustomerId("cus_unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sendWebhook("checkout.session.completed", """
                {"id": "cs_1", "object": "checkout.session", "customer": "cus_unknown", "subscription": "sub_new"}
                """)).hasMessageContaining("User not found");
    }

    @Test
    void webhook_subscriptionEventForAnUnknownUser_isSkipped() throws Exception {
        Subscription subscription = stripeSubscription("sub_x", "active", false);
        subscription.setCustomer("cus_unknown");
        when(stripeService.retrieveSubscription("sub_x")).thenReturn(subscription);
        when(subscriptionRepository.findByStripeSubscriptionId("sub_x")).thenReturn(Optional.empty());
        when(userRepository.findByStripeCustomerId("cus_unknown")).thenReturn(Optional.empty());

        sendWebhook("customer.subscription.updated", "{\"id\": \"sub_x\", \"object\": \"subscription\"}");

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void webhook_inactiveSubscriptionWithNoLocalRecord_isNotCreated() throws Exception {
        User user = new User();
        user.setId(userId);
        Subscription incomplete = stripeSubscription("sub_x", "incomplete", false);
        incomplete.setCustomer("cus_1");
        stubPlans();
        when(stripeService.retrieveSubscription("sub_x")).thenReturn(incomplete);
        when(subscriptionRepository.findByStripeSubscriptionId("sub_x")).thenReturn(Optional.empty());
        when(userRepository.findByStripeCustomerId("cus_1")).thenReturn(Optional.of(user));

        sendWebhook("customer.subscription.created", "{\"id\": \"sub_x\", \"object\": \"subscription\"}");

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void webhook_unpaidSubscription_isSuspended() throws Exception {
        UserSubscription local = activeSubscription("sub_1");
        stubPlans();
        when(stripeService.retrieveSubscription("sub_1")).thenReturn(stripeSubscription("sub_1", "unpaid", false));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(local));

        sendWebhook("customer.subscription.updated", "{\"id\": \"sub_1\", \"object\": \"subscription\"}");

        assertThat(local.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
    }

    @Test
    void webhook_oneOffInvoices_andUnhandledEvents_areIgnored() throws Exception {
        sendWebhook("invoice.payment_succeeded", "{\"id\": \"in_1\", \"object\": \"invoice\"}");
        sendWebhook("customer.created", "{\"id\": \"cus_1\", \"object\": \"customer\"}");

        verify(stripeService, never()).retrieveSubscription(anyString());
    }

    @Test
    void webhook_priceNotMatchingAnyPlan_failsSoStripeRetries() throws Exception {
        UserSubscription local = activeSubscription("sub_1");
        when(planRepository.findByStripePriceId(MONTHLY_PRICE)).thenReturn(Optional.empty());
        when(stripeService.retrieveSubscription("sub_1")).thenReturn(stripeSubscription("sub_1", "active", false));
        when(subscriptionRepository.findByStripeSubscriptionId("sub_1")).thenReturn(Optional.of(local));

        assertThatThrownBy(() -> sendWebhook("customer.subscription.updated",
                "{\"id\": \"sub_1\", \"object\": \"subscription\"}"))
                .hasMessageContaining("Plan not found for price");
    }

    private UsageTracking usage(int count) {
        UsageTracking usage = new UsageTracking();
        usage.setUsageCount(count);
        return usage;
    }

    // --- helpers ---

    private void stubFlag(String featureName, String requiredPlan) {
        FeatureFlag flag = new FeatureFlag();
        flag.setFeatureName(featureName);
        flag.setIsEnabled(true);
        flag.setRequiredPlan(requiredPlan);
        when(featureFlagRepository.findByFeatureName(featureName)).thenReturn(Optional.of(flag));
    }

    private void stubCurrentPlan(String planName) {
        if ("FREE".equals(planName)) {
            when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());
            when(planRepository.findByName("FREE")).thenReturn(Optional.of(plan("FREE")));
        } else {
            UserSubscription sub = activeSubscription("sub_1");
            sub.setPlan(plan(planName));
            when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(sub));
            when(planRepository.findByName("FREE")).thenReturn(Optional.of(plan("FREE")));
        }
    }

    private void stubUsage(String featureName, int count) {
        UsageTracking usage = new UsageTracking();
        usage.setUsageCount(count);
        when(usageRepository.findByUserIdAndFeatureNameAndPeriod(any(), any(), any(), any()))
                .thenReturn(Optional.of(usage));
    }

    private void stubPlans() {
        SubscriptionPlan premium = plan("PREMIUM");
        premium.setStripePriceIdMonthly(MONTHLY_PRICE);
        // Looked up by price ID in the database, not by loading every plan
        when(planRepository.findByStripePriceId(MONTHLY_PRICE)).thenReturn(Optional.of(premium));
    }

    private void sendWebhook(String type, String objectJson) throws Exception {
        sendWebhook(type, objectJson, Stripe.API_VERSION);
    }

    private void sendWebhook(String type, String objectJson, String apiVersion) throws Exception {
        String payload = eventJson(type, objectJson, apiVersion);
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));

        subscriptionService.handleStripeWebhook(payload, "t=" + timestamp + ",v1=" + signature);
    }

    private String eventJson(String type, String objectJson, String apiVersion) {
        return """
                {"id": "evt_1", "object": "event", "type": "%s", "api_version": "%s",
                 "data": {"object": %s}}
                """.formatted(type, apiVersion, objectJson);
    }

    private UserSubscription activeSubscription(String stripeSubscriptionId) {
        UserSubscription sub = new UserSubscription();
        sub.setUserId(userId);
        sub.setPlan(plan("PREMIUM"));
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setBillingCycle(BillingCycle.MONTHLY);
        sub.setEndDate(LocalDateTime.now().plusDays(10));
        sub.setStripeSubscriptionId(stripeSubscriptionId);
        sub.setAutoRenew(true);
        return sub;
    }

    private SubscriptionPlan plan(String name) {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setName(name);
        return plan;
    }

    private Subscription stripeSubscription(String id, String status, boolean cancelAtPeriodEnd) {
        return stripeSubscription(id, status, cancelAtPeriodEnd,
                Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond());
    }

    private Subscription stripeSubscription(String id, String status, boolean cancelAtPeriodEnd, long periodEnd) {
        Price.Recurring recurring = new Price.Recurring();
        recurring.setInterval("month");
        Price price = new Price();
        price.setId(MONTHLY_PRICE);
        price.setRecurring(recurring);

        SubscriptionItem item = new SubscriptionItem();
        item.setCurrentPeriodEnd(periodEnd);
        item.setPrice(price);
        SubscriptionItemCollection items = new SubscriptionItemCollection();
        items.setData(List.of(item));

        Subscription subscription = new Subscription();
        subscription.setId(id);
        subscription.setStatus(status);
        subscription.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        subscription.setItems(items);
        return subscription;
    }
}

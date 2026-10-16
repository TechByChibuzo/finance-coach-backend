package com.financecoach.backend.service;

import com.financecoach.backend.model.BillingCycle;
import com.financecoach.backend.model.SubscriptionPlan;
import com.financecoach.backend.model.SubscriptionStatus;
import com.financecoach.backend.model.User;
import com.financecoach.backend.model.UserSubscription;
import com.financecoach.backend.repository.SubscriptionPlanRepository;
import com.financecoach.backend.repository.UserRepository;
import com.financecoach.backend.repository.UserSubscriptionRepository;
import com.stripe.Stripe;
import com.stripe.exception.ApiConnectionException;
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
import java.util.HexFormat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not cancel");

        verify(subscriptionRepository, never()).save(any());
        assertThat(sub.getAutoRenew()).isTrue();
    }

    @Test
    void cancelSubscription_withNoActiveSubscription_throws() {
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.cancelSubscription(userId))
                .hasMessageContaining("No active subscription");
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
    void createSubscription_onUpgrade_cancelsPreviousStripeSubscription() throws Exception {
        UserSubscription previous = activeSubscription("sub_old");
        when(subscriptionRepository.findActiveByUserId(userId)).thenReturn(Optional.of(previous));

        subscriptionService.createSubscription(userId, plan("PRO"), BillingCycle.MONTHLY,
                stripeSubscription("sub_new", "active", false));

        verify(stripeService).cancelSubscription("sub_old");
        assertThat(previous.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
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

    // --- helpers ---

    private void stubPlans() {
        SubscriptionPlan premium = plan("PREMIUM");
        premium.setStripePriceIdMonthly(MONTHLY_PRICE);
        when(planRepository.findAll()).thenReturn(List.of(plan("FREE"), premium));
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

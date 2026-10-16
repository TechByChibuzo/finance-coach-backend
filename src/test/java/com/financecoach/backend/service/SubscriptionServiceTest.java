package com.financecoach.backend.service;

import com.financecoach.backend.model.BillingCycle;
import com.financecoach.backend.model.SubscriptionPlan;
import com.financecoach.backend.model.SubscriptionStatus;
import com.financecoach.backend.model.User;
import com.financecoach.backend.model.UserSubscription;
import com.financecoach.backend.repository.SubscriptionPlanRepository;
import com.financecoach.backend.repository.UserRepository;
import com.financecoach.backend.repository.UserSubscriptionRepository;
import com.stripe.exception.ApiConnectionException;
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

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
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

    // --- helpers ---

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
        SubscriptionItem item = new SubscriptionItem();
        item.setCurrentPeriodEnd(periodEnd);
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

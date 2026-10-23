package com.financecoach.backend.repository;

import com.financecoach.backend.model.BillingCycle;
import com.financecoach.backend.model.SubscriptionPlan;
import com.financecoach.backend.model.SubscriptionStatus;
import com.financecoach.backend.model.UserSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findActiveByUserId decides which plan a user is on (no row means FREE).
 */
class ActiveSubscriptionTest extends PostgresRepositoryTest {

    @Autowired private UserSubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionPlanRepository planRepository;

    private SubscriptionPlan premium;
    private UUID userId;

    @BeforeEach
    void setUp() {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setName("PREMIUM");
        plan.setDisplayName("Premium");
        premium = planRepository.saveAndFlush(plan);
        userId = UUID.randomUUID();
    }

    @Test
    void aPaidSubscriptionWithinItsPeriod_isActive() {
        save(SubscriptionStatus.ACTIVE, LocalDateTime.now().plusDays(10));

        assertThat(subscriptionRepository.findActiveByUserId(userId)).isPresent();
    }

    @Test
    void aTrialThatHasntEnded_givesAccessToItsPlan() {
        save(SubscriptionStatus.TRIAL, LocalDateTime.now().plusDays(10));

        assertThat(subscriptionRepository.findActiveByUserId(userId))
                .hasValueSatisfying(s -> assertThat(s.getPlan().getName()).isEqualTo("PREMIUM"));
    }

    @Test
    void anEndedTrial_orACancelledSubscription_isNotActive() {
        save(SubscriptionStatus.TRIAL, LocalDateTime.now().minusDays(1));
        save(SubscriptionStatus.CANCELLED, LocalDateTime.now().plusDays(10));

        assertThat(subscriptionRepository.findActiveByUserId(userId)).isEmpty();
    }

    private void save(SubscriptionStatus status, LocalDateTime endDate) {
        UserSubscription subscription = new UserSubscription();
        subscription.setUserId(userId);
        subscription.setPlan(premium);
        subscription.setStatus(status);
        subscription.setBillingCycle(BillingCycle.MONTHLY);
        subscription.setEndDate(endDate);
        if (status == SubscriptionStatus.TRIAL) {
            subscription.setTrialEndDate(endDate);
        }
        subscriptionRepository.saveAndFlush(subscription);
    }
}

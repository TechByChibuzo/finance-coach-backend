package com.financecoach.backend.repository;

import com.financecoach.backend.model.SubscriptionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stripe webhooks name a price; the plan is found by either of its price IDs in one query.
 */
class PlanByStripePriceTest extends PostgresRepositoryTest {

    @Autowired private SubscriptionPlanRepository planRepository;

    @Test
    void findsThePlanByItsMonthlyOrYearlyPrice_andNothingForAnUnknownPrice() {
        SubscriptionPlan premium = new SubscriptionPlan();
        premium.setName("PREMIUM");
        premium.setDisplayName("Premium");
        premium.setStripePriceIdMonthly("price_premium_monthly");
        premium.setStripePriceIdYearly("price_premium_yearly");
        planRepository.saveAndFlush(premium);
        SubscriptionPlan free = new SubscriptionPlan();
        free.setName("FREE");
        free.setDisplayName("Free");
        planRepository.saveAndFlush(free);

        assertThat(planRepository.findByStripePriceId("price_premium_monthly"))
                .hasValueSatisfying(p -> assertThat(p.getName()).isEqualTo("PREMIUM"));
        assertThat(planRepository.findByStripePriceId("price_premium_yearly"))
                .hasValueSatisfying(p -> assertThat(p.getName()).isEqualTo("PREMIUM"));
        assertThat(planRepository.findByStripePriceId("price_unknown")).isEmpty();
    }
}

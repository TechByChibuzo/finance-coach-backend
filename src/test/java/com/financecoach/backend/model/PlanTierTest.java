package com.financecoach.backend.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanTierTest {

    @Test
    void aPlanMeetsItsOwnTierAndEveryLowerOne() {
        assertThat(PlanTier.meets("PRO", "PREMIUM")).isTrue();
        assertThat(PlanTier.meets("PRO", "PRO")).isTrue();
        assertThat(PlanTier.meets("PREMIUM", "FREE")).isTrue();
        assertThat(PlanTier.meets("FREE", "PREMIUM")).isFalse();
        assertThat(PlanTier.meets("PREMIUM", "PRO")).isFalse();
    }

    @Test
    void namesAreMatchedIgnoringCase_andAnUnknownPlanMeetsNoPaidTier() {
        assertThat(PlanTier.meets("premium", "Premium")).isTrue();
        assertThat(PlanTier.meets("ENTERPRISE", "PREMIUM")).isFalse();
        assertThat(PlanTier.meets(null, "FREE")).isFalse();
    }

    @Test
    void aFeatureWithoutAKnownRequiredPlan_isOpenToEveryone() {
        // Feature flags that don't name a tier stay open, as before
        assertThat(PlanTier.meets("FREE", null)).isTrue();
        assertThat(PlanTier.meets("FREE", "")).isTrue();
    }
}

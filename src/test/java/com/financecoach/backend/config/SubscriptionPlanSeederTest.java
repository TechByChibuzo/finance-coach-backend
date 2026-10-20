package com.financecoach.backend.config;

import com.financecoach.backend.model.FeatureFlag;
import com.financecoach.backend.model.SubscriptionPlan;
import com.financecoach.backend.repository.FeatureFlagRepository;
import com.financecoach.backend.repository.SubscriptionPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionPlanSeederTest {

    @Mock private SubscriptionPlanRepository planRepository;
    @Mock private FeatureFlagRepository featureFlagRepository;

    @Test
    void emptyDatabase_getsAllPlansAndFlags_withConfiguredPriceIds() {
        when(planRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(featureFlagRepository.findByFeatureName(anyString())).thenReturn(Optional.empty());

        seeder("price_pm", "price_py", "", "").run(null);

        ArgumentCaptor<SubscriptionPlan> plans = ArgumentCaptor.forClass(SubscriptionPlan.class);
        verify(planRepository, times(3)).save(plans.capture());
        assertThat(plans.getAllValues()).extracting(SubscriptionPlan::getName)
                .containsExactly("FREE", "PREMIUM", "PRO");
        SubscriptionPlan premium = plans.getAllValues().get(1);
        assertThat(premium.getStripePriceIdMonthly()).isEqualTo("price_pm");
        assertThat(premium.getStripePriceIdYearly()).isEqualTo("price_py");
        assertThat(plans.getAllValues().get(2).getStripePriceIdMonthly()).isNull();

        ArgumentCaptor<FeatureFlag> flags = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(featureFlagRepository, times(6)).save(flags.capture());
        assertThat(flags.getAllValues()).extracting(FeatureFlag::getFeatureName)
                .contains("ai_coach", "export_reports");
    }

    @Test
    void existingRows_areLeftAlone_whenNothingIsConfigured() {
        when(planRepository.findByName(anyString())).thenAnswer(inv -> Optional.of(plan(inv.getArgument(0))));
        when(featureFlagRepository.findByFeatureName(anyString())).thenReturn(Optional.of(new FeatureFlag()));

        seeder("", "", "", "").run(null);

        verify(planRepository, never()).save(any());
        verify(featureFlagRepository, never()).save(any());
    }

    @Test
    void existingPlan_getsNewPriceIdFromTheEnvironment() {
        SubscriptionPlan pro = plan("PRO");
        when(planRepository.findByName(anyString())).thenAnswer(inv ->
                "PRO".equals(inv.getArgument(0)) ? Optional.of(pro) : Optional.of(plan(inv.getArgument(0))));
        when(featureFlagRepository.findByFeatureName(anyString())).thenReturn(Optional.of(new FeatureFlag()));

        seeder("", "", "price_pro_m", "").run(null);

        verify(planRepository).save(pro);
        assertThat(pro.getStripePriceIdMonthly()).isEqualTo("price_pro_m");
    }

    private SubscriptionPlanSeeder seeder(String pm, String py, String prom, String proy) {
        return new SubscriptionPlanSeeder(planRepository, featureFlagRepository, pm, py, prom, proy);
    }

    private SubscriptionPlan plan(String name) {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setName(name);
        return plan;
    }
}

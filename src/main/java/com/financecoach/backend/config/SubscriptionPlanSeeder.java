package com.financecoach.backend.config;

import com.financecoach.backend.model.FeatureFlag;
import com.financecoach.backend.model.SubscriptionPlan;
import com.financecoach.backend.repository.FeatureFlagRepository;
import com.financecoach.backend.repository.SubscriptionPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Seeds the subscription plans and feature flags the app depends on (getUserPlan needs a
 * FREE plan; gated endpoints need their feature_flags rows). Runs on every startup and is
 * idempotent: missing rows are created, existing rows are left alone, and Stripe price IDs
 * are filled in from the environment when configured.
 */
@Component
public class SubscriptionPlanSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionPlanSeeder.class);

    private record PlanSeed(String name, String displayName, String description,
                            String priceMonthly, String priceYearly, String features, String limits,
                            String stripePriceIdMonthly, String stripePriceIdYearly) {}

    private record FlagSeed(String featureName, String requiredPlan, String description) {}

    private static final List<FlagSeed> FLAGS = List.of(
            new FlagSeed("ai_coach", "FREE", "AI-powered financial coaching"),
            new FlagSeed("budget_create", "FREE", "Create budgets"),
            new FlagSeed("export_reports", "PREMIUM", "Export financial reports"),
            new FlagSeed("advanced_analytics", "PREMIUM", "Advanced analytics dashboard"),
            new FlagSeed("family_sharing", "PRO", "Share with family members"),
            new FlagSeed("api_access", "PRO", "API access for developers")
    );

    private final SubscriptionPlanRepository planRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final List<PlanSeed> plans;

    public SubscriptionPlanSeeder(SubscriptionPlanRepository planRepository,
                                  FeatureFlagRepository featureFlagRepository,
                                  @Value("${stripe.prices.premium-monthly:}") String premiumMonthly,
                                  @Value("${stripe.prices.premium-yearly:}") String premiumYearly,
                                  @Value("${stripe.prices.pro-monthly:}") String proMonthly,
                                  @Value("${stripe.prices.pro-yearly:}") String proYearly) {
        this.planRepository = planRepository;
        this.featureFlagRepository = featureFlagRepository;
        this.plans = List.of(
                new PlanSeed("FREE", "Free Plan", "Perfect for getting started with basic budgeting",
                        "0.00", "0.00",
                        "{\"ai_coach\": true, \"budgets\": true, \"transactions\": true, \"reports\": false, \"export\": false, \"analytics\": false}",
                        "{\"max_budgets\": 3, \"ai_messages_per_month\": 5, \"bank_accounts\": 1, \"transaction_history_days\": 30}",
                        null, null),
                new PlanSeed("PREMIUM", "Premium Plan", "Unlimited budgets and AI coaching",
                        "9.99", "99.99",
                        "{\"ai_coach\": true, \"budgets\": true, \"transactions\": true, \"reports\": true, \"export\": true, \"analytics\": true, \"unlimited_history\": true}",
                        "{\"max_budgets\": -1, \"ai_messages_per_month\": -1, \"bank_accounts\": 5, \"transaction_history_days\": -1}",
                        premiumMonthly, premiumYearly),
                new PlanSeed("PRO", "Pro Plan", "Everything in Premium plus family sharing and API access",
                        "19.99", "199.99",
                        "{\"ai_coach\": true, \"budgets\": true, \"transactions\": true, \"reports\": true, \"export\": true, \"analytics\": true, \"unlimited_history\": true, \"family_sharing\": true, \"api_access\": true, \"priority_support\": true}",
                        "{\"max_budgets\": -1, \"ai_messages_per_month\": -1, \"bank_accounts\": -1, \"transaction_history_days\": -1, \"family_members\": 5}",
                        proMonthly, proYearly)
        );
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        plans.forEach(this::seedPlan);
        FLAGS.forEach(this::seedFlag);
    }

    private void seedPlan(PlanSeed seed) {
        SubscriptionPlan plan = planRepository.findByName(seed.name()).orElse(null);
        boolean changed = false;

        if (plan == null) {
            plan = new SubscriptionPlan();
            plan.setName(seed.name());
            plan.setDisplayName(seed.displayName());
            plan.setDescription(seed.description());
            plan.setPriceMonthly(new BigDecimal(seed.priceMonthly()));
            plan.setPriceYearly(new BigDecimal(seed.priceYearly()));
            plan.setFeatures(seed.features());
            plan.setLimits(seed.limits());
            plan.setIsActive(true);
            changed = true;
            logger.info("Seeding subscription plan {}", seed.name());
        }

        // Price IDs differ per Stripe account/mode, so they come from the environment
        if (StringUtils.hasText(seed.stripePriceIdMonthly())
                && !Objects.equals(seed.stripePriceIdMonthly(), plan.getStripePriceIdMonthly())) {
            plan.setStripePriceIdMonthly(seed.stripePriceIdMonthly());
            changed = true;
        }
        if (StringUtils.hasText(seed.stripePriceIdYearly())
                && !Objects.equals(seed.stripePriceIdYearly(), plan.getStripePriceIdYearly())) {
            plan.setStripePriceIdYearly(seed.stripePriceIdYearly());
            changed = true;
        }

        if (changed) {
            plan.setUpdatedAt(LocalDateTime.now());
            planRepository.save(plan);
        }
    }

    private void seedFlag(FlagSeed seed) {
        if (featureFlagRepository.findByFeatureName(seed.featureName()).isPresent()) {
            return;
        }

        FeatureFlag flag = new FeatureFlag();
        flag.setFeatureName(seed.featureName());
        flag.setIsEnabled(true);
        flag.setRequiredPlan(seed.requiredPlan());
        flag.setRolloutPercentage(100);
        flag.setDescription(seed.description());
        featureFlagRepository.save(flag);
        logger.info("Seeding feature flag {}", seed.featureName());
    }
}

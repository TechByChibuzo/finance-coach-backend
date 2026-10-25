package com.financecoach.backend.model;

/**
 * The subscription plans, lowest first. Plans are stored by name (subscription_plans.name,
 * feature_flags.required_plan); this is the one place that knows their order.
 */
public enum PlanTier {
    FREE,
    PREMIUM,
    PRO;

    /**
     * Whether a user on {@code userPlan} may use something that requires {@code requiredPlan}.
     * Names are matched ignoring case. Nothing required (null, blank or an unknown name) is open
     * to everyone; an unknown user plan meets no tier.
     */
    public static boolean meets(String userPlan, String requiredPlan) {
        PlanTier required = from(requiredPlan);
        if (required == null) {
            return true;
        }
        PlanTier user = from(userPlan);
        return user != null && user.compareTo(required) >= 0;
    }

    private static PlanTier from(String name) {
        if (name == null) {
            return null;
        }
        for (PlanTier tier : values()) {
            if (tier.name().equalsIgnoreCase(name.trim())) {
                return tier;
            }
        }
        return null;
    }
}

package com.financecoach.backend.aspect;

import com.financecoach.backend.annotation.RequiresFeature;
import com.financecoach.backend.annotation.RequiresPlan;
import com.financecoach.backend.annotation.TrackUsage;
import com.financecoach.backend.exception.FeatureNotAvailableException;
import com.financecoach.backend.exception.UsageLimitExceededException;
import com.financecoach.backend.service.SubscriptionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
public class FeatureAccessAspect {

    @Autowired
    private SubscriptionService subscriptionService;

    /**
     * Check if user has required plan before method execution
     */
    @Around("@annotation(requiresPlan)")
    public Object checkPlanAccess(ProceedingJoinPoint joinPoint, RequiresPlan requiresPlan) throws Throwable {
        UUID userId = getCurrentUserId();

        String userPlan = subscriptionService.getUserPlan(userId).getName();
        String requiredPlan = requiresPlan.value();

        if (!hasRequiredPlan(userPlan, requiredPlan)) {
            throw new FeatureNotAvailableException(requiresPlan.message());
        }

        return joinPoint.proceed();
    }

    /**
     * Check if user has access to feature before method execution
     */
    @Around("@annotation(requiresFeature)")
    public Object checkFeatureAccess(ProceedingJoinPoint joinPoint, RequiresFeature requiresFeature) throws Throwable {
        UUID userId = getCurrentUserId();
        String featureName = requiresFeature.value();

        // Check if user can use this feature
        if (!subscriptionService.canUseFeature(userId, featureName)) {
            // Check if it's access or usage limit issue
            if (!subscriptionService.hasFeatureAccess(userId, featureName)) {
                throw new FeatureNotAvailableException(requiresFeature.message());
            } else {
                throw new UsageLimitExceededException("Usage limit exceeded for " + featureName);
            }
        }

        return joinPoint.proceed();
    }

    /**
     * Track usage after successful method execution
     */
    @Around("@annotation(trackUsage)")
    public Object trackFeatureUsage(ProceedingJoinPoint joinPoint, TrackUsage trackUsage) throws Throwable {
        UUID userId = getCurrentUserId();
        String featureName = trackUsage.feature();

        // Execute method
        Object result = joinPoint.proceed();

        // Track usage after successful execution
        subscriptionService.trackUsage(userId, featureName);

        return result;
    }

    private UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        // Assuming you store UUID in principal
        return UUID.fromString(auth.getName());
    }

    private boolean hasRequiredPlan(String userPlan, String requiredPlan) {
        int userLevel = getPlanLevel(userPlan);
        int requiredLevel = getPlanLevel(requiredPlan);
        return userLevel >= requiredLevel;
    }

    private int getPlanLevel(String planName) {
        return switch (planName.toUpperCase()) {
            case "PRO" -> 3;
            case "PREMIUM" -> 2;
            case "FREE" -> 1;
            default -> 0;
        };
    }
}
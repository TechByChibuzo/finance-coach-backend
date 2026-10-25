package com.financecoach.backend.aspect;

import com.financecoach.backend.annotation.RequiresFeature;
import com.financecoach.backend.annotation.RequiresPlan;
import com.financecoach.backend.annotation.TrackUsage;
import com.financecoach.backend.exception.FeatureNotAvailableException;
import com.financecoach.backend.exception.UsageLimitExceededException;
import com.financecoach.backend.model.PlanTier;
import com.financecoach.backend.security.CurrentUser;
import com.financecoach.backend.service.SubscriptionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
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
        UUID userId = CurrentUser.id();

        String userPlan = subscriptionService.getUserPlan(userId).getName();
        if (!PlanTier.meets(userPlan, requiresPlan.value().name())) {
            throw new FeatureNotAvailableException(requiresPlan.message());
        }

        return joinPoint.proceed();
    }

    /**
     * Check if user has access to feature before method execution
     */
    @Around("@annotation(requiresFeature)")
    public Object checkFeatureAccess(ProceedingJoinPoint joinPoint, RequiresFeature requiresFeature) throws Throwable {
        UUID userId = CurrentUser.id();
        String featureName = requiresFeature.value();

        // When the method also counts usage of this feature, @TrackUsage reserves it atomically and
        // enforces the limit; checking the count here too would refuse the last allowed use if that
        // reservation happened first. Only check plan access then.
        if (countsUsageOf(joinPoint, featureName)) {
            if (!subscriptionService.hasFeatureAccess(userId, featureName)) {
                throw new FeatureNotAvailableException(requiresFeature.message());
            }
            return joinPoint.proceed();
        }

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
        UUID userId = CurrentUser.id();
        String featureName = trackUsage.feature();

        // Reserve the use up front (atomic, enforces the plan limit), and give it back if the call
        // fails, so failed requests don't use up the allowance
        subscriptionService.reserveUsage(userId, featureName);
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            subscriptionService.releaseUsage(userId, featureName);
            throw e;
        }
    }

    private boolean countsUsageOf(ProceedingJoinPoint joinPoint, String featureName) {
        TrackUsage trackUsage = ((MethodSignature) joinPoint.getSignature()).getMethod()
                .getAnnotation(TrackUsage.class);
        return trackUsage != null && trackUsage.feature().equals(featureName);
    }


}
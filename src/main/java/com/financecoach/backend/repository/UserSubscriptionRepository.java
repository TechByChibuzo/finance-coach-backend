package com.financecoach.backend.repository;

import com.financecoach.backend.model.UserSubscription;
import com.financecoach.backend.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, UUID> {

    /**
     * Find user's active subscription
     */
    @Query("SELECT s FROM UserSubscription s WHERE s.userId = :userId " +
            "AND s.status = 'ACTIVE' AND (s.endDate IS NULL OR s.endDate > CURRENT_TIMESTAMP)")
    Optional<UserSubscription> findActiveByUserId(@Param("userId") UUID userId);

    /**
     * Find all subscriptions for a user (including expired)
     */
    List<UserSubscription> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Find subscriptions by status
     */
    List<UserSubscription> findByStatus(SubscriptionStatus status);

    /**
     * Find subscriptions expiring soon
     */
    @Query("SELECT s FROM UserSubscription s WHERE s.status = 'ACTIVE' " +
            "AND s.endDate BETWEEN :now AND :endDate")
    List<UserSubscription> findExpiringSoon(
            @Param("now") LocalDateTime now,
            @Param("endDate") LocalDateTime endDate
    );

    /**
     * Find by Stripe subscription ID
     */
    Optional<UserSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);

//    /**
//     * Find by Stripe customer ID
//     */
//    Optional<UserSubscription> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Count active subscriptions by plan
     */
    @Query("SELECT COUNT(s) FROM UserSubscription s WHERE s.plan.id = :planId AND s.status = 'ACTIVE'")
    Long countActiveByPlanId(@Param("planId") UUID planId);

    /**
     * Find any subscription for user (active or not)
     */
    Optional<UserSubscription> findByUserId(UUID userId);
}
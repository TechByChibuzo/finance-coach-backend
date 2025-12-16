package com.financecoach.backend.repository;

import com.financecoach.backend.model.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    /**
     * Find plan by name (FREE, PREMIUM, PRO)
     */
    Optional<SubscriptionPlan> findByName(String name);

    /**
     * Find all active plans
     */
    List<SubscriptionPlan> findByIsActiveTrue();

    /**
     * Check if plan exists by name
     */
    boolean existsByName(String name);
}

package com.financecoach.backend.repository;

import com.financecoach.backend.model.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {

    /**
     * Find feature flag by name
     */
    Optional<FeatureFlag> findByFeatureName(String featureName);

    /**
     * Find all enabled features
     */
    List<FeatureFlag> findByIsEnabledTrue();

    /**
     * Find features by required plan
     */
    List<FeatureFlag> findByRequiredPlan(String requiredPlan);

}

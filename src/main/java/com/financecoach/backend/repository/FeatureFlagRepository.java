package com.financecoach.backend.repository;

import com.financecoach.backend.model.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Find features available to a plan (includes lower tier features)
     */
    @Query("SELECT f FROM FeatureFlag f WHERE f.isEnabled = true " +
            "AND (f.requiredPlan IS NULL OR f.requiredPlan IN :plans)")
    List<FeatureFlag> findFeaturesForPlan(@Param("plans") List<String> plans);

    /**
     * Check if feature exists and is enabled
     */
    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END " +
            "FROM FeatureFlag f WHERE f.featureName = :featureName AND f.isEnabled = true")
    boolean isFeatureEnabled(@Param("featureName") String featureName);
}

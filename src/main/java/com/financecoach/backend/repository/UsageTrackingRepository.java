package com.financecoach.backend.repository;

import com.financecoach.backend.model.UsageTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsageTrackingRepository extends JpaRepository<UsageTracking, UUID> {

    /**
     * Find usage for user and feature in date range
     */
    @Query("SELECT u FROM UsageTracking u WHERE u.userId = :userId " +
            "AND u.featureName = :featureName " +
            "AND u.periodStart <= :date AND u.periodEnd >= :date")
    Optional<UsageTracking> findByUserIdAndFeatureNameAndPeriod(
            @Param("userId") UUID userId,
            @Param("featureName") String featureName,
            @Param("date") LocalDate periodStart,
            @Param("date") LocalDate periodEnd
    );

    /**
     * Find all usage for a user in current period
     */
    @Query("SELECT u FROM UsageTracking u WHERE u.userId = :userId " +
            "AND u.periodStart <= CURRENT_DATE AND u.periodEnd >= CURRENT_DATE")
    List<UsageTracking> findCurrentUsageByUserId(@Param("userId") UUID userId);

    /**
     * Find usage by feature
     */
    List<UsageTracking> findByFeatureName(String featureName);

    /**
     * Get total usage count for feature
     */
    @Query("SELECT SUM(u.usageCount) FROM UsageTracking u WHERE u.featureName = :featureName " +
            "AND u.periodStart >= :startDate")
    Long getTotalUsageForFeature(
            @Param("featureName") String featureName,
            @Param("startDate") LocalDate startDate
    );

    /**
     * Delete old tracking records (cleanup)
     */
    void deleteByPeriodEndBefore(LocalDate date);

    /**
     * Find top users by feature usage
     */
    @Query("SELECT u.userId, SUM(u.usageCount) as total FROM UsageTracking u " +
            "WHERE u.featureName = :featureName AND u.periodStart >= :startDate " +
            "GROUP BY u.userId ORDER BY total DESC")
    List<Object[]> findTopUsersByFeature(
            @Param("featureName") String featureName,
            @Param("startDate") LocalDate startDate
    );
}
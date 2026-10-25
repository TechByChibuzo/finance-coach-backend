package com.financecoach.backend.repository;

import com.financecoach.backend.model.UsageTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
     * Atomically count one use if the user is under the limit. Creates the period's counter on first
     * use; otherwise increments it only while usage_count < limit. Postgres locks the row, so
     * concurrent requests can't both take the last slot.
     *
     * @return 1 if the use was counted, 0 if the limit was already reached
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO usage_tracking (id, user_id, feature_name, usage_count, period_start, period_end, created_at)
            VALUES (gen_random_uuid(), :userId, :featureName, 1, :periodStart, :periodEnd, NOW())
            ON CONFLICT (user_id, feature_name, period_start)
            DO UPDATE SET usage_count = usage_tracking.usage_count + 1
            WHERE usage_tracking.usage_count < :limit
            """, nativeQuery = true)
    int reserveUsage(@Param("userId") UUID userId,
                     @Param("featureName") String featureName,
                     @Param("periodStart") LocalDate periodStart,
                     @Param("periodEnd") LocalDate periodEnd,
                     @Param("limit") int limit);

    /**
     * Give back a reserved use (the request it was reserved for failed)
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE usage_tracking SET usage_count = usage_count - 1
            WHERE user_id = :userId AND feature_name = :featureName
              AND period_start = :periodStart AND usage_count > 0
            """, nativeQuery = true)
    int releaseUsage(@Param("userId") UUID userId,
                     @Param("featureName") String featureName,
                     @Param("periodStart") LocalDate periodStart);

    /**
     * Find usage by feature
     */
    List<UsageTracking> findByFeatureName(String featureName);

    /**
     * Delete old tracking records (cleanup)
     */
    void deleteByPeriodEndBefore(LocalDate date);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM UsageTracking u WHERE u.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

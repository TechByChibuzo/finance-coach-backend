package com.financecoach.backend.repository;

import com.financecoach.backend.model.SpendingAnomaly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface SpendingAnomalyRepository extends JpaRepository<SpendingAnomaly, UUID> {

    List<SpendingAnomaly> findByUserIdOrderByDetectedAtDesc(UUID userId);

    List<SpendingAnomaly> findByUserIdAndIsReviewedFalseOrderByDetectedAtDesc(UUID userId);

    List<SpendingAnomaly> findByUserIdAndCategoryOrderByDetectedAtDesc(
            UUID userId, String category
    );

    // A list, not Optional: rows created before anomalies were keyed by month can be duplicated
    List<SpendingAnomaly> findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(
            UUID userId, String category, LocalDate periodStart
    );

    long countByUserIdAndIsReviewedFalse(UUID userId);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM SpendingAnomaly s WHERE s.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

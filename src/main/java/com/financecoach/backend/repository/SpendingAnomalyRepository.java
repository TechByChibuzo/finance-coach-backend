package com.financecoach.backend.repository;

import com.financecoach.backend.model.SpendingAnomaly;
import org.springframework.data.jpa.repository.JpaRepository;
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

    boolean existsByUserIdAndCategoryAndPeriodStartAndPeriodEnd(
            UUID userId, String category, LocalDate periodStart, LocalDate periodEnd
    );

    long countByUserIdAndIsReviewedFalse(UUID userId);
}
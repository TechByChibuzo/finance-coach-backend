package com.financecoach.backend.repository;

import com.financecoach.backend.model.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    List<Holding> findByUserId(UUID userId);

    List<Holding> findByAccountId(UUID accountId);

    Optional<Holding> findByAccountIdAndSecurityId(UUID accountId, String securityId);

    @Query("SELECT h.type, SUM(h.currentValue) FROM Holding h WHERE h.userId = :userId GROUP BY h.type")
    List<Object[]> getAssetAllocationByUserId(@Param("userId") UUID userId);

    @Query("SELECT COALESCE(SUM(h.currentValue), 0) FROM Holding h WHERE h.userId = :userId")
    java.math.BigDecimal getTotalPortfolioValueByUserId(@Param("userId") UUID userId);
}
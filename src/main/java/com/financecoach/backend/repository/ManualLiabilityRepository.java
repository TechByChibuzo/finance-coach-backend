package com.financecoach.backend.repository;

import com.financecoach.backend.model.ManualLiability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ManualLiabilityRepository extends JpaRepository<ManualLiability, UUID> {

    List<ManualLiability> findByUserId(UUID userId);

    @Query("SELECT COALESCE(SUM(l.balance), 0) FROM ManualLiability l WHERE l.userId = :userId")
    BigDecimal getTotalBalanceByUserId(UUID userId);
}
package com.financecoach.backend.repository;

import com.financecoach.backend.model.ManualAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ManualAssetRepository extends JpaRepository<ManualAsset, UUID> {

    List<ManualAsset> findByUserId(UUID userId);

    @Query("SELECT COALESCE(SUM(a.value), 0) FROM ManualAsset a WHERE a.userId = :userId")
    BigDecimal getTotalValueByUserId(UUID userId);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM ManualAsset m WHERE m.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

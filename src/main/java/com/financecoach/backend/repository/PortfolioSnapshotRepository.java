package com.financecoach.backend.repository;

import com.financecoach.backend.model.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, UUID> {

    List<PortfolioSnapshot> findByUserIdOrderBySnapshotDateDesc(UUID userId);

    List<PortfolioSnapshot> findByUserIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
            UUID userId, LocalDate startDate);

    Optional<PortfolioSnapshot> findByUserIdAndSnapshotDate(UUID userId, LocalDate snapshotDate);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM PortfolioSnapshot p WHERE p.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

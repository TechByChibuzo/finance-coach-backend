package com.financecoach.backend.repository;

import com.financecoach.backend.model.NetWorthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, UUID> {

    List<NetWorthSnapshot> findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<NetWorthSnapshot> findByUserIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
            UUID userId,
            LocalDate startDate
    );

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM NetWorthSnapshot n WHERE n.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

package com.financecoach.backend.repository;

import com.financecoach.backend.model.NetWorthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, UUID> {

    List<NetWorthSnapshot> findByUserIdOrderBySnapshotDateDesc(UUID userId);

    List<NetWorthSnapshot> findByUserIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
            UUID userId, LocalDate startDate);

    Optional<NetWorthSnapshot> findByUserIdAndSnapshotDate(UUID userId, LocalDate snapshotDate);
}
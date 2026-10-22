package com.financecoach.backend.repository;

import com.financecoach.backend.model.SavingsGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, UUID> {

    List<SavingsGoal> findByUserIdOrderByCreatedAtAsc(UUID userId);

    long countByUserId(UUID userId);

    // Account deletion: remove all of the user's goals in one statement
    @Modifying
    @Query("DELETE FROM SavingsGoal g WHERE g.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

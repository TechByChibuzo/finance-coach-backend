// src/main/java/com/financecoach/userservice/repository/BudgetRepository.java
package com.financecoach.backend.repository;

import com.financecoach.backend.model.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    // Find all budgets for a user
    List<Budget> findByUserId(UUID userId);

    // Find active budgets for a user
    List<Budget> findByUserIdAndIsActive(UUID userId, Boolean isActive);

    // Find budgets for a specific month
    List<Budget> findByUserIdAndMonth(UUID userId, LocalDate month);

    // Find active budgets for a specific month
    List<Budget> findByUserIdAndMonthAndIsActive(UUID userId, LocalDate month, Boolean isActive);

    // Find budget for specific category and month
    Optional<Budget> findByUserIdAndCategoryAndMonth(UUID userId, String category, LocalDate month);

    // Check if budget exists for category and month
    boolean existsByUserIdAndCategoryAndMonth(UUID userId, String category, LocalDate month);

    // Find budgets that are exceeded
    @Query("SELECT b FROM Budget b WHERE b.userId = :userId AND b.isActive = true AND b.spent > b.amount")
    List<Budget> findExceededBudgets(@Param("userId") UUID userId);

    // Find budgets that should trigger alerts
    @Query("SELECT b FROM Budget b WHERE b.userId = :userId AND b.isActive = true " +
            "AND (b.spent / b.amount * 100) >= b.alertThreshold")
    List<Budget> findBudgetsNeedingAlert(@Param("userId") UUID userId);

    // Get total budget amount for a month
    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM Budget b " +
            "WHERE b.userId = :userId AND b.month = :month AND b.isActive = true")
    Double getTotalBudgetForMonth(@Param("userId") UUID userId, @Param("month") LocalDate month);

    // Get total spent for a month
    @Query("SELECT COALESCE(SUM(b.spent), 0) FROM Budget b " +
            "WHERE b.userId = :userId AND b.month = :month AND b.isActive = true")
    Double getTotalSpentForMonth(@Param("userId") UUID userId, @Param("month") LocalDate month);

    // Find budgets by date range
    @Query("SELECT b FROM Budget b WHERE b.userId = :userId " +
            "AND b.month >= :startMonth AND b.month <= :endMonth " +
            "AND b.isActive = true")
    List<Budget> findByUserIdAndMonthBetween(@Param("userId") UUID userId,
                                             @Param("startMonth") LocalDate startMonth,
                                             @Param("endMonth") LocalDate endMonth);
}
// src/main/java/com/financecoach/userservice/repository/TransactionRepository.java
package com.financecoach.userservice.repository;

import com.financecoach.userservice.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByUserId(UUID userId);

    List<Transaction> findByUserIdAndDateBetween(UUID userId, LocalDate startDate, LocalDate endDate);

    List<Transaction> findByAccountId(UUID accountId);

    Optional<Transaction> findByPlaidTransactionId(String plaidTransactionId);

    boolean existsByPlaidTransactionId(String plaidTransactionId);

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.category = :category")
    List<Transaction> findByUserIdAndCategory(@Param("userId") UUID userId, @Param("category") String category);
}
// src/main/java/com/financecoach/userservice/repository/TransactionRepository.java
package com.financecoach.backend.repository;

import com.financecoach.backend.model.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
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

    // Users with any transactions, for jobs that run per user
    @Query("SELECT DISTINCT t.userId FROM Transaction t")
    List<UUID> findDistinctUserIds();

    // Which of these transaction IDs are already stored (for skipping duplicates on import)
    @Query("SELECT t.plaidTransactionId FROM Transaction t WHERE t.plaidTransactionId IN :ids")
    List<String> findExistingPlaidTransactionIds(@Param("ids") Collection<String> ids);

    @Transactional
    @Modifying
    @Query("DELETE FROM Transaction t WHERE t.userId = :userId AND t.importBatchId = :batchId")
    int deleteImportBatch(@Param("userId") UUID userId, @Param("batchId") UUID batchId);

    // Transactions on these accounts that have no embedding yet (e.g. their batch failed), newest first
    @Query("SELECT t FROM Transaction t WHERE t.accountId IN :accountIds AND NOT EXISTS "
            + "(SELECT e.id FROM TransactionEmbedding e WHERE e.transactionId = t.plaidTransactionId) "
            + "ORDER BY t.date DESC")
    List<Transaction> findWithoutEmbedding(@Param("accountIds") Collection<UUID> accountIds, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.category = :category")
    List<Transaction> findByUserIdAndCategory(@Param("userId") UUID userId, @Param("category") String category);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM Transaction t WHERE t.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

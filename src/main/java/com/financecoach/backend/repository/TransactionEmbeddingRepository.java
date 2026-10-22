package com.financecoach.backend.repository;

import com.financecoach.backend.model.TransactionEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TransactionEmbeddingRepository extends JpaRepository<TransactionEmbedding, UUID> {

    boolean existsByTransactionId(String transactionId);

    // A bulk delete: Hibernate can't read the vector column, so nothing here may load embedding
    // entities (a derived deleteBy... would, and fail). Similarity search lives in VectorStoreService.
    @Modifying
    @Query("DELETE FROM TransactionEmbedding t WHERE t.transactionId = :transactionId")
    int deleteByTransactionId(@Param("transactionId") String transactionId);

    long countByUserId(UUID userId);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM TransactionEmbedding t WHERE t.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

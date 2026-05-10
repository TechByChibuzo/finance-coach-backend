package com.financecoach.backend.repository;

import com.financecoach.backend.model.TransactionEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionEmbeddingRepository extends JpaRepository<TransactionEmbedding, UUID> {

    boolean existsByTransactionId(String transactionId);

    void deleteByTransactionId(String transactionId);

    // Core RAG query — cosine similarity search scoped to a user
    @Query(value = """
        SELECT * FROM transaction_embeddings
        WHERE user_id = :userId
        ORDER BY embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<TransactionEmbedding> findSimilarTransactions(
            @Param("userId") UUID userId,
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("limit") int limit
    );

    List<TransactionEmbedding> findByUserId(UUID userId);

    long countByUserId(UUID userId);
}
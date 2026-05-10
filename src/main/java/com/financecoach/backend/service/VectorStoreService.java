package com.financecoach.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.TransactionEmbedding;
import com.financecoach.backend.repository.TransactionEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);
    private static final int DEFAULT_RETRIEVAL_LIMIT = 10;

    private final TransactionEmbeddingRepository embeddingRepository;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public VectorStoreService(TransactionEmbeddingRepository embeddingRepository,
                              EmbeddingService embeddingService,
                              JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper) {
        this.embeddingRepository = embeddingRepository;
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void indexTransaction(Transaction transaction) {
        if (embeddingRepository.existsByTransactionId(transaction.getPlaidTransactionId())) {
            logger.debug("Transaction {} already indexed, skipping", transaction.getPlaidTransactionId());
            return;
        }

        try {
            String content = embeddingService.buildTransactionText(transaction);
            float[] embedding = embeddingService.embedTransaction(transaction);

            Map<String, Object> metadata = Map.of(
                    "amount", transaction.getAmount(),
                    "category", transaction.getCategory() != null ? transaction.getCategory() : "Uncategorized",
                    "date", transaction.getDate().toString(),
                    "merchant", transaction.getMerchantName() != null
                            ? transaction.getMerchantName()
                            : transaction.getName()
            );

            String vectorString = buildVectorString(embedding);
            String metadataJson = objectMapper.writeValueAsString(metadata);

            jdbcTemplate.update(
                    "INSERT INTO transaction_embeddings (id, transaction_id, user_id, embedding, content, metadata, created_at) " +
                            "VALUES (gen_random_uuid(), ?, ?, ?::vector, ?, ?::jsonb, NOW())",
                    transaction.getPlaidTransactionId(),
                    transaction.getUserId(),
                    vectorString,
                    content,
                    metadataJson
            );

            logger.debug("Indexed transaction {}", transaction.getPlaidTransactionId());

        } catch (Exception e) {
            logger.error("Failed to index transaction {}: {}",
                    transaction.getPlaidTransactionId(), e.getMessage());
        }
    }

    public List<String> retrieveRelevantContext(UUID userId, String query, int limit) {
        logger.debug("Retrieving RAG context for query: '{}'", query);

        float[] queryEmbedding = embeddingService.embedText(query);
        String vectorString = buildVectorString(queryEmbedding);

        List<String> results = jdbcTemplate.query(
                "SELECT content FROM transaction_embeddings " +
                        "WHERE user_id = ? " +
                        "ORDER BY embedding <=> ?::vector " +
                        "LIMIT ?",
                (rs, rowNum) -> rs.getString("content"),
                userId,
                vectorString,
                limit
        );

        logger.debug("Retrieved {} relevant transactions", results.size());
        return results;
    }

    public List<String> retrieveRelevantContext(UUID userId, String query) {
        return retrieveRelevantContext(userId, query, DEFAULT_RETRIEVAL_LIMIT);
    }

    public long getIndexedTransactionCount(UUID userId) {
        return embeddingRepository.countByUserId(userId);
    }

    private String buildVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
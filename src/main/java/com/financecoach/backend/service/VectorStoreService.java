package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.TransactionEmbedding;
import com.financecoach.backend.repository.TransactionEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public VectorStoreService(TransactionEmbeddingRepository embeddingRepository,
                              EmbeddingService embeddingService) {
        this.embeddingRepository = embeddingRepository;
        this.embeddingService = embeddingService;
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

            TransactionEmbedding record = new TransactionEmbedding(
                    transaction.getPlaidTransactionId(),
                    transaction.getUserId(),
                    embedding,
                    content,
                    metadata
            );

            embeddingRepository.save(record);
            logger.debug("Indexed transaction {}", transaction.getPlaidTransactionId());

        } catch (Exception e) {
            logger.error("Failed to index transaction {}: {}",
                    transaction.getPlaidTransactionId(), e.getMessage());
        }
    }

    public List<String> retrieveRelevantContext(UUID userId, String query, int limit) {
        logger.debug("Retrieving RAG context for query: '{}'", query);

        float[] queryEmbedding = embeddingService.embedText(query);

        List<TransactionEmbedding> results = embeddingRepository
                .findSimilarTransactions(userId, queryEmbedding, limit);

        logger.debug("Retrieved {} relevant transactions", results.size());

        return results.stream()
                .map(TransactionEmbedding::getContent)
                .toList();
    }

    public List<String> retrieveRelevantContext(UUID userId, String query) {
        return retrieveRelevantContext(userId, query, DEFAULT_RETRIEVAL_LIMIT);
    }

    public long getIndexedTransactionCount(UUID userId) {
        return embeddingRepository.countByUserId(userId);
    }
}
package com.financecoach.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.TransactionEmbedding;
import com.financecoach.backend.repository.TransactionEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreService.class);
    private static final int DEFAULT_RETRIEVAL_LIMIT = 10;
    private static final int EMBEDDING_BATCH_SIZE = 100;

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

    public void indexTransaction(Transaction transaction) {
        indexTransactions(List.of(transaction));
    }

    /**
     * Index transactions in the background so syncing doesn't wait on the embedding API.
     */
    @Async("embeddingExecutor")
    public void indexTransactionsAsync(List<Transaction> transactions) {
        indexTransactions(transactions);
    }

    /**
     * Embed and store transactions that aren't indexed yet, EMBEDDING_BATCH_SIZE per API call.
     * A failed batch is logged and skipped; the rest still get indexed.
     */
    public void indexTransactions(List<Transaction> transactions) {
        List<Transaction> pending = transactions.stream()
                .filter(t -> !embeddingRepository.existsByTransactionId(t.getPlaidTransactionId()))
                .toList();

        int indexed = 0;
        for (int start = 0; start < pending.size(); start += EMBEDDING_BATCH_SIZE) {
            List<Transaction> batch = pending.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, pending.size()));
            try {
                List<String> texts = batch.stream().map(embeddingService::buildTransactionText).toList();
                List<float[]> embeddings = embeddingService.embedTexts(texts);

                for (int i = 0; i < batch.size(); i++) {
                    insertEmbedding(batch.get(i), texts.get(i), embeddings.get(i));
                }
                indexed += batch.size();

            } catch (Exception e) {
                logger.error("Failed to index a batch of {} transactions: {}", batch.size(), e.getMessage());
            }
        }

        if (!pending.isEmpty()) {
            logger.info("Indexed {}/{} transactions", indexed, pending.size());
        }
    }

    private void insertEmbedding(Transaction transaction, String content, float[] embedding) throws Exception {
        Map<String, Object> metadata = Map.of(
                "amount", transaction.getAmount(),
                "category", transaction.getCategory() != null ? transaction.getCategory() : "Uncategorized",
                "date", transaction.getDate().toString(),
                "merchant", transaction.getMerchantName() != null
                        ? transaction.getMerchantName()
                        : transaction.getName()
        );

        // Runs in the background, so the transaction may have been deleted (or the account erased)
        // since it was synced: only insert while the transaction still exists. The foreign key with
        // ON DELETE CASCADE (DatabaseConstraints) covers a delete racing this statement.
        // Plaid may also have modified it since: only insert while it still has the fields the text
        // was built from, and replace older text, so the job for the newest version wins.
        jdbcTemplate.update(
                "INSERT INTO transaction_embeddings (id, transaction_id, user_id, embedding, content, metadata, created_at) " +
                        "SELECT gen_random_uuid(), ?, ?, ?::vector, ?, ?::jsonb, NOW() " +
                        "WHERE EXISTS (SELECT 1 FROM transactions WHERE plaid_transaction_id = ? " +
                        "AND amount = ? AND date = ? AND merchant_name IS NOT DISTINCT FROM ? " +
                        "AND name IS NOT DISTINCT FROM ? AND category IS NOT DISTINCT FROM ? AND account_id = ?) " +
                        "ON CONFLICT (transaction_id) DO UPDATE SET embedding = EXCLUDED.embedding, " +
                        "content = EXCLUDED.content, metadata = EXCLUDED.metadata, created_at = EXCLUDED.created_at",
                transaction.getPlaidTransactionId(),
                transaction.getUserId(),
                buildVectorString(embedding),
                content,
                objectMapper.writeValueAsString(metadata),
                transaction.getPlaidTransactionId(),
                transaction.getAmount(),
                transaction.getDate(),
                transaction.getMerchantName(),
                transaction.getName(),
                transaction.getCategory(),
                transaction.getAccountId()
        );
    }

    @Transactional
    public void removeTransaction(String plaidTransactionId) {
        embeddingRepository.deleteByTransactionId(plaidTransactionId);
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
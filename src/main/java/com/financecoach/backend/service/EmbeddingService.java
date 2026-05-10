package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embedText(String text) {
        return embeddingModel.embed(text);
    }

    public float[] embedTransaction(Transaction transaction) {
        String text = buildTransactionText(transaction);
        logger.debug("Embedding transaction {}: {}", transaction.getPlaidTransactionId(), text);
        return embedText(text);
    }

    public String buildTransactionText(Transaction transaction) {
        return String.format(
                "Transaction: $%.2f at %s on %s. Category: %s. Account: %s. %s",
                transaction.getAmount().doubleValue(),
                transaction.getMerchantName() != null
                        ? transaction.getMerchantName()
                        : transaction.getName(),
                transaction.getDate(),
                transaction.getCategory() != null ? transaction.getCategory() : "Uncategorized",
                transaction.getAccountId().toString(),
                transaction.getAmount().doubleValue() > 0
                        ? "This was a purchase/debit."
                        : "This was a credit/refund."
        );
    }
}
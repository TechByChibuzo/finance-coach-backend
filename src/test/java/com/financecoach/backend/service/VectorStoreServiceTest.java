package com.financecoach.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorStoreServiceTest {

    @Mock private TransactionEmbeddingRepository embeddingRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private JdbcTemplate jdbcTemplate;

    private VectorStoreService vectorStoreService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        vectorStoreService = new VectorStoreService(embeddingRepository, embeddingService, jdbcTemplate, new ObjectMapper());
        userId = UUID.randomUUID();
    }

    @Test
    void indexTransaction_storesTheEmbeddingAsAPgvectorLiteral() {
        Transaction tx = transaction();
        when(embeddingRepository.existsByTransactionId("tx-1")).thenReturn(false);
        when(embeddingService.buildTransactionText(tx)).thenReturn("Transaction: $5.00 at Uber");
        when(embeddingService.embedTransaction(tx)).thenReturn(new float[]{0.5f, -1.25f});

        vectorStoreService.indexTransaction(tx);

        verify(jdbcTemplate).update(contains("INSERT INTO transaction_embeddings"),
                eq("tx-1"), eq(userId), eq("[0.5,-1.25]"), eq("Transaction: $5.00 at Uber"), contains("\"merchant\":\"Uber\""));
    }

    @Test
    void indexTransaction_skipsTransactionsAlreadyIndexed() {
        when(embeddingRepository.existsByTransactionId("tx-1")).thenReturn(true);

        vectorStoreService.indexTransaction(transaction());

        verify(embeddingService, never()).embedTransaction(any());
    }

    @Test
    void indexTransaction_whenEmbeddingFails_doesNotThrow() {
        Transaction tx = transaction();
        when(embeddingRepository.existsByTransactionId("tx-1")).thenReturn(false);
        when(embeddingService.buildTransactionText(tx)).thenReturn("text");
        when(embeddingService.embedTransaction(tx)).thenThrow(new RuntimeException("OpenAI down"));

        vectorStoreService.indexTransaction(tx);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveRelevantContext_searchesOnlyTheUsersEmbeddings() {
        when(embeddingService.embedText("coffee")).thenReturn(new float[]{1f});
        when(jdbcTemplate.query(contains("WHERE user_id = ?"), any(RowMapper.class), eq(userId), eq("[1.0]"), eq(10)))
                .thenReturn(List.of("Starbucks $5.00"));

        assertThat(vectorStoreService.retrieveRelevantContext(userId, "coffee")).containsExactly("Starbucks $5.00");
    }

    @Test
    void removeTransaction_andCount_delegateToTheRepository() {
        when(embeddingRepository.countByUserId(userId)).thenReturn(7L);

        vectorStoreService.removeTransaction("tx-1");

        verify(embeddingRepository).deleteByTransactionId("tx-1");
        assertThat(vectorStoreService.getIndexedTransactionCount(userId)).isEqualTo(7L);
    }

    private Transaction transaction() {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setPlaidTransactionId("tx-1");
        t.setAmount(new BigDecimal("5.00"));
        t.setMerchantName("Uber");
        t.setCategory("TRANSPORTATION");
        t.setDate(LocalDate.of(2026, 9, 15));
        return t;
    }
}

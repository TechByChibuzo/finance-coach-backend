package com.financecoach.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        Transaction tx = transaction("tx-1");
        when(embeddingRepository.existsByTransactionId("tx-1")).thenReturn(false);
        when(embeddingService.buildTransactionText(tx)).thenReturn("Transaction: $5.00 at Uber");
        when(embeddingService.embedTexts(List.of("Transaction: $5.00 at Uber")))
                .thenReturn(List.of(new float[]{0.5f, -1.25f}));

        vectorStoreService.indexTransaction(tx);

        verify(jdbcTemplate).update(contains("WHERE EXISTS (SELECT 1 FROM transactions WHERE plaid_transaction_id = ?"),
                eq("tx-1"), eq(userId), eq("[0.5,-1.25]"), eq("Transaction: $5.00 at Uber"),
                contains("\"merchant\":\"Uber\""), eq("tx-1"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void indexTransaction_onlyStoresTextBuiltFromTheTransactionAsItStillIs_andReplacesOlderText() {
        // A job that started before Plaid modified the transaction must not store its stale text,
        // and the job for the modified version must replace whatever is there
        Transaction tx = transaction("tx-1");
        UUID accountId = UUID.randomUUID();
        tx.setAccountId(accountId);
        tx.setName("UBER *TRIP");
        when(embeddingRepository.existsByTransactionId("tx-1")).thenReturn(false);
        when(embeddingService.buildTransactionText(tx)).thenReturn("text");
        when(embeddingService.embedTexts(List.of("text"))).thenReturn(List.of(new float[]{1f}));

        vectorStoreService.indexTransaction(tx);

        verify(jdbcTemplate).update(
                and(contains("WHERE EXISTS (SELECT 1 FROM transactions WHERE plaid_transaction_id = ? "
                                + "AND amount = ? AND date = ? AND merchant_name IS NOT DISTINCT FROM ? "
                                + "AND name IS NOT DISTINCT FROM ? AND category IS NOT DISTINCT FROM ? AND account_id = ?)"),
                        contains("ON CONFLICT (transaction_id) DO UPDATE SET")),
                eq("tx-1"), eq(userId), eq("[1.0]"), eq("text"), anyString(),
                eq("tx-1"), eq(new BigDecimal("5.00")), eq(LocalDate.of(2026, 9, 15)), eq("Uber"),
                eq("UBER *TRIP"), eq("TRANSPORTATION"), eq(accountId));
    }

    @Test
    void indexTransactions_embedsInBatchesOfOneHundred_skippingOnesAlreadyIndexed() {
        List<Transaction> transactions = IntStream.range(0, 250).mapToObj(i -> transaction("tx-" + i)).toList();
        when(embeddingRepository.existsByTransactionId(anyString())).thenAnswer(inv -> inv.getArgument(0).equals("tx-0"));
        when(embeddingService.buildTransactionText(any())).thenAnswer(inv -> ((Transaction) inv.getArgument(0)).getPlaidTransactionId());
        when(embeddingService.embedTexts(any())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(t -> new float[]{1f}).toList());

        vectorStoreService.indexTransactions(transactions);

        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(embeddingService, times(3)).embedTexts(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(100, 100, 49);
        verify(jdbcTemplate, times(249)).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void indexTransactions_aFailedBatchIsSkipped_andTheRestStillIndexed() {
        List<Transaction> transactions = IntStream.range(0, 150).mapToObj(i -> transaction("tx-" + i)).toList();
        when(embeddingRepository.existsByTransactionId(anyString())).thenReturn(false);
        when(embeddingService.buildTransactionText(any())).thenReturn("text");
        when(embeddingService.embedTexts(any()))
                .thenThrow(new RuntimeException("OpenAI rate limit"))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream().map(t -> new float[]{1f}).toList());

        vectorStoreService.indexTransactions(transactions);

        verify(jdbcTemplate, times(50)).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void indexTransaction_skipsTransactionsAlreadyIndexed() {
        when(embeddingRepository.existsByTransactionId("tx-1")).thenReturn(true);

        vectorStoreService.indexTransaction(transaction("tx-1"));

        verify(embeddingService, never()).embedTexts(any());
    }

    @Test
    void indexTransactionsAsync_indexesTheGivenTransactions() {
        when(embeddingRepository.existsByTransactionId("tx-1")).thenReturn(true);

        vectorStoreService.indexTransactionsAsync(List.of(transaction("tx-1")));

        verify(embeddingRepository).existsByTransactionId("tx-1");
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

    private Transaction transaction(String plaidTransactionId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setPlaidTransactionId(plaidTransactionId);
        t.setAmount(new BigDecimal("5.00"));
        t.setMerchantName("Uber");
        t.setCategory("TRANSPORTATION");
        t.setDate(LocalDate.of(2026, 9, 15));
        return t;
    }
}

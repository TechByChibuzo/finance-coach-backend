package com.financecoach.backend.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.User;
import com.financecoach.backend.service.EmbeddingService;
import com.financecoach.backend.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Storing embeddings in pgvector: the guarded insert in VectorStoreService (embedding jobs run in
 * the background, after the transaction may have changed or gone), and findWithoutEmbedding.
 */
class EmbeddingStorageTest extends PostgresRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionEmbeddingRepository embeddingRepository;

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private VectorStoreService vectorStoreService;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        vectorStoreService = new VectorStoreService(embeddingRepository, embeddingService, jdbcTemplate, new ObjectMapper());
        userId = userRepository.saveAndFlush(new User("jane@example.com", "hash", "Jane")).getId();
        accountId = UUID.randomUUID();
        when(embeddingService.buildTransactionText(any())).thenAnswer(inv -> "text for $" + ((Transaction) inv.getArgument(0)).getAmount());
        when(embeddingService.embedTexts(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(t -> vector(0.1f)).toList());
    }

    @Test
    void indexingAStoredTransaction_storesItsEmbedding() {
        Transaction tx = transactionRepository.saveAndFlush(transaction("tx-1", "12.50"));

        vectorStoreService.indexTransaction(tx);

        assertThat(content("tx-1")).containsExactly("text for $12.50");
    }

    @Test
    void aTransactionDeletedBeforeItsJobFinishes_getsNoEmbedding() {
        Transaction neverSaved = transaction("tx-gone", "12.50");

        vectorStoreService.indexTransaction(neverSaved);

        assertThat(content("tx-gone")).isEmpty();
    }

    @Test
    void aJobForAVersionPlaidHasSinceModified_storesNothing() {
        transactionRepository.saveAndFlush(transaction("tx-1", "15.00"));
        Transaction beforePlaidChangedIt = transaction("tx-1", "12.50");

        vectorStoreService.indexTransaction(beforePlaidChangedIt);

        assertThat(content("tx-1")).isEmpty();
    }

    @Test
    void textStoredByAnotherJobMeanwhile_isReplacedByTheCurrentVersions() {
        Transaction tx = transactionRepository.saveAndFlush(transaction("tx-1", "15.00"));
        // Another job stored its text after this one checked whether the transaction was indexed
        TransactionEmbeddingRepository checkedBeforeTheOtherJob = mock(TransactionEmbeddingRepository.class);
        jdbcTemplate.update("INSERT INTO transaction_embeddings (id, transaction_id, user_id, embedding, content, created_at) "
                + "VALUES (gen_random_uuid(), 'tx-1', ?, ?::vector, 'outdated text', NOW())", userId, Arrays.toString(vector(0.2f)));

        new VectorStoreService(checkedBeforeTheOtherJob, embeddingService, jdbcTemplate, new ObjectMapper())
                .indexTransaction(tx);

        assertThat(content("tx-1")).containsExactly("text for $15.00");
    }

    @Test
    void deletingATransaction_removesItsEmbedding() {
        Transaction tx = transactionRepository.saveAndFlush(transaction("tx-1", "12.50"));
        vectorStoreService.indexTransaction(tx);

        jdbcTemplate.update("DELETE FROM transactions WHERE plaid_transaction_id = 'tx-1'");

        assertThat(content("tx-1")).isEmpty();
    }

    @Test
    void findWithoutEmbedding_returnsTheAccountsUnindexedTransactions_newestFirst_skippingHiddenAndSplit() {
        Transaction older = transaction("tx-older", "1.00");
        older.setDate(LocalDate.of(2026, 9, 1));
        Transaction newer = transaction("tx-newer", "2.00");
        newer.setDate(LocalDate.of(2026, 9, 20));
        Transaction indexed = transaction("tx-indexed", "3.00");
        Transaction hidden = transaction("tx-hidden", "4.00");
        hidden.setHidden(true);
        Transaction split = transaction("tx-split", "5.00");
        split.setSplit(true);
        Transaction otherAccount = transaction("tx-other-account", "6.00");
        otherAccount.setAccountId(UUID.randomUUID());
        transactionRepository.saveAllAndFlush(List.of(older, newer, indexed, hidden, split, otherAccount));
        vectorStoreService.indexTransaction(indexed);

        List<Transaction> pending = transactionRepository.findWithoutEmbedding(List.of(accountId), PageRequest.of(0, 10));

        assertThat(pending).extracting(Transaction::getPlaidTransactionId).containsExactly("tx-newer", "tx-older");
    }

    private Transaction transaction(String plaidTransactionId, String amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setPlaidTransactionId(plaidTransactionId);
        t.setDate(LocalDate.of(2026, 9, 15));
        t.setAmount(new BigDecimal(amount));
        t.setMerchantName("Uber");
        t.setCategory("TRANSPORTATION");
        return t;
    }

    private List<String> content(String plaidTransactionId) {
        return jdbcTemplate.queryForList(
                "SELECT content FROM transaction_embeddings WHERE transaction_id = ?", String.class, plaidTransactionId);
    }

    private static float[] vector(float value) {
        float[] v = new float[1536];
        Arrays.fill(v, value);
        return v;
    }
}

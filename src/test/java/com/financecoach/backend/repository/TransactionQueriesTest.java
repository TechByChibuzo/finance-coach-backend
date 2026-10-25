package com.financecoach.backend.repository;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.User;
import com.financecoach.backend.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TransactionRepository's search, de-duplication and bulk deletes, against real Postgres.
 */
class TransactionQueriesTest extends PostgresRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;

    private UUID jane;
    private UUID other;
    private UUID account;

    @BeforeEach
    void users() {
        jane = userRepository.saveAndFlush(new User("jane@example.com", "hash", "Jane")).getId();
        other = userRepository.saveAndFlush(new User("other@example.com", "hash", "Other")).getId();
        account = UUID.randomUUID();
    }

    @Test
    void search_matchesNameOrMerchant_ignoringCase_newestFirst_onlyForTheUser() {
        save(jane, "tx-1", LocalDate.of(2026, 9, 1), "STARBUCKS #123", null);
        save(jane, "tx-2", LocalDate.of(2026, 9, 20), "CARD PURCHASE", "Starbucks");
        save(jane, "tx-3", LocalDate.of(2026, 9, 10), "Uber trip", "Uber");
        save(other, "tx-4", LocalDate.of(2026, 9, 15), "Starbucks", "Starbucks");

        assertThat(search("starbucks")).containsExactly("tx-2", "tx-1");
    }

    @Test
    void search_treatsPercentAndUnderscoreLiterally() {
        save(jane, "tx-pct", LocalDate.of(2026, 9, 1), "50% OFF SALE", null);
        save(jane, "tx-plain", LocalDate.of(2026, 9, 2), "500 PURCHASE", null);
        save(jane, "tx-under", LocalDate.of(2026, 9, 3), "ACME_CORP", null);
        save(jane, "tx-x", LocalDate.of(2026, 9, 4), "ACMEXCORP", null);

        assertThat(search("50%")).containsExactly("tx-pct");
        assertThat(search("acme_corp")).containsExactly("tx-under");
    }

    @Test
    void findExistingPlaidTransactionIds_returnsOnlyTheOnesAlreadyStored() {
        save(jane, "tx-1", LocalDate.of(2026, 9, 1), "A", null);
        save(jane, "tx-2", LocalDate.of(2026, 9, 1), "B", null);

        assertThat(transactionRepository.findExistingPlaidTransactionIds(List.of("tx-1", "tx-2", "tx-new")))
                .containsExactlyInAnyOrder("tx-1", "tx-2");
    }

    @Test
    void deleteImportBatch_removesOnlyThatUsersBatch() {
        UUID batch = UUID.randomUUID();
        Transaction imported = save(jane, "csv-1", LocalDate.of(2026, 9, 1), "Imported", null);
        imported.setImportBatchId(batch);
        transactionRepository.saveAndFlush(imported);
        Transaction othersSameBatchId = save(other, "csv-2", LocalDate.of(2026, 9, 1), "Imported", null);
        othersSameBatchId.setImportBatchId(batch);
        transactionRepository.saveAndFlush(othersSameBatchId);
        save(jane, "plaid-1", LocalDate.of(2026, 9, 1), "From the bank", null);

        assertThat(transactionRepository.deleteImportBatch(jane, batch)).isEqualTo(1);
        assertThat(ids()).containsExactlyInAnyOrder("csv-2", "plaid-1");
    }

    @Test
    void deleteByAccountId_andDeleteBySplitFromId_removeOnlyTheirRows() {
        UUID duplicateAccount = UUID.randomUUID();
        Transaction copy = save(jane, "dup-1", LocalDate.of(2026, 9, 1), "Copy", null);
        copy.setAccountId(duplicateAccount);
        transactionRepository.saveAndFlush(copy);
        Transaction original = save(jane, "orig", LocalDate.of(2026, 9, 1), "Costco", null);
        Transaction part = save(jane, "part", LocalDate.of(2026, 9, 1), "Costco part", null);
        part.setSplitFromId(original.getId());
        transactionRepository.saveAndFlush(part);

        assertThat(transactionRepository.deleteByAccountId(duplicateAccount)).isEqualTo(1);
        assertThat(transactionRepository.deleteBySplitFromId(original.getId())).isEqualTo(1);
        assertThat(ids()).containsExactly("orig");
    }

    @Test
    void findByUserIdAndCategory_andFindDistinctUserIds() {
        Transaction food = save(jane, "tx-food", LocalDate.of(2026, 9, 1), "Lunch", null);
        food.setCategory("FOOD_AND_DRINK");
        transactionRepository.saveAndFlush(food);
        save(jane, "tx-other", LocalDate.of(2026, 9, 2), "Gas", null);
        save(other, "tx-o", LocalDate.of(2026, 9, 2), "Gas", null);

        assertThat(transactionRepository.findByUserIdAndCategory(jane, "FOOD_AND_DRINK"))
                .extracting(Transaction::getPlaidTransactionId).containsExactly("tx-food");
        assertThat(transactionRepository.findDistinctUserIds()).containsExactlyInAnyOrder(jane, other);
    }

    private List<String> search(String text) {
        TransactionService service = new TransactionService(
                null, transactionRepository, null, null, null, null, null, null);
        return service.searchTransactions(jane, text, 50).stream()
                .map(Transaction::getPlaidTransactionId).toList();
    }

    private Transaction save(UUID userId, String plaidId, LocalDate date, String name, String merchant) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(account);
        t.setPlaidTransactionId(plaidId);
        t.setDate(date);
        t.setAmount(new BigDecimal("10.00"));
        t.setName(name);
        t.setMerchantName(merchant);
        return transactionRepository.saveAndFlush(t);
    }

    private List<String> ids() {
        return jdbcTemplate.queryForList("SELECT plaid_transaction_id FROM transactions", String.class);
    }
}

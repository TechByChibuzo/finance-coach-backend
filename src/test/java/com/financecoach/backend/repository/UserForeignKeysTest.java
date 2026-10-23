package com.financecoach.backend.repository;

import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user foreign keys DatabaseConstraints adds: a background sync that finishes after the
 * account was deleted can't write bank data back.
 */
class UserForeignKeysTest extends PostgresRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PlaidItemRepository plaidItemRepository;
    @Autowired private BankAccountRepository bankAccountRepository;
    @Autowired private TransactionRepository transactionRepository;

    @Test
    void aBankConnectionSavedForADeletedUser_isRejected() {
        UUID deletedUserId = UUID.randomUUID();

        assertThatThrownBy(() -> plaidItemRepository.saveAndFlush(plaidItem(deletedUserId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // One rejected write per test: Postgres aborts the (test) transaction after the first

    @Test
    void anAccountSavedForADeletedUser_isRejected() {
        assertThatThrownBy(() -> bankAccountRepository.saveAndFlush(bankAccount(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTransactionSavedForADeletedUser_isRejected() {
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(transaction(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAUser_removesTheirBankData() {
        UUID userId = userRepository.saveAndFlush(new User("jane@example.com", "hash", "Jane")).getId();
        plaidItemRepository.saveAndFlush(plaidItem(userId));
        bankAccountRepository.saveAndFlush(bankAccount(userId));
        transactionRepository.saveAndFlush(transaction(userId));

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);

        for (String table : new String[] {"plaid_items", "bank_accounts", "transactions"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId))
                    .as(table).isZero();
        }
    }

    private static PlaidItem plaidItem(UUID userId) {
        PlaidItem item = new PlaidItem();
        item.setUserId(userId);
        item.setPlaidItemId("item-" + UUID.randomUUID());
        item.setAccessToken("access-sandbox-token");
        return item;
    }

    private static BankAccount bankAccount(UUID userId) {
        BankAccount account = new BankAccount();
        account.setUserId(userId);
        account.setPlaidAccountId("acc-" + UUID.randomUUID());
        account.setPlaidAccessToken("access-sandbox-token");
        return account;
    }

    private static Transaction transaction(UUID userId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(UUID.randomUUID());
        t.setPlaidTransactionId("tx-" + UUID.randomUUID());
        t.setDate(LocalDate.of(2026, 9, 15));
        t.setAmount(new BigDecimal("12.50"));
        return t;
    }
}

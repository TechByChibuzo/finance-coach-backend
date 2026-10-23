package com.financecoach.backend.repository;

import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan limits count bank connections. Access tokens are stored encrypted with a random salt,
 * so these run the real converter and SQL.
 */
class BankConnectionCountTest extends PostgresRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private BankAccountRepository bankAccountRepository;
    @Autowired private TestEntityManager entityManager;

    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = userRepository.saveAndFlush(new User("jane@example.com", "hash", "Jane")).getId();
    }

    @Test
    void theSameTokenIsStoredDifferentlyOnEveryAccount() {
        bankAccountRepository.saveAndFlush(legacyAccount("access-legacy"));
        bankAccountRepository.saveAndFlush(legacyAccount("access-legacy"));

        List<String> stored = jdbcTemplate.queryForList(
                "SELECT plaid_access_token FROM bank_accounts WHERE user_id = ?", String.class, userId);

        assertThat(stored).hasSize(2).doesNotContain("access-legacy");
        assertThat(stored.get(0)).isNotEqualTo(stored.get(1));
    }

    @Test
    void legacyAccountsSharingAToken_countAsOneConnection() {
        bankAccountRepository.saveAndFlush(legacyAccount("access-legacy"));   // checking
        bankAccountRepository.saveAndFlush(legacyAccount("access-legacy"));   // savings at the same bank
        bankAccountRepository.saveAndFlush(legacyAccount("access-other"));
        // Read them back from the database (decrypting), not the entities still in the session
        entityManager.clear();

        assertThat(bankAccountRepository.countLegacyConnections(userId)).isEqualTo(2);
    }

    @Test
    void connectionsAddUpItemsAndLegacyAccounts_ignoringDisconnectedOnes() {
        UUID chase = UUID.randomUUID();
        bankAccountRepository.saveAndFlush(itemAccount(chase));
        bankAccountRepository.saveAndFlush(itemAccount(chase));                // a second account, same item
        bankAccountRepository.saveAndFlush(itemAccount(UUID.randomUUID()));
        bankAccountRepository.saveAndFlush(legacyAccount("access-legacy"));
        BankAccount disconnected = itemAccount(UUID.randomUUID());
        disconnected.setIsActive(false);
        bankAccountRepository.saveAndFlush(disconnected);

        assertThat(bankAccountRepository.countConnectedItems(userId)).isEqualTo(2);
        assertThat(bankAccountRepository.countBankConnections(userId)).isEqualTo(3);
    }

    private BankAccount legacyAccount(String accessToken) {
        BankAccount account = new BankAccount();
        account.setUserId(userId);
        account.setPlaidAccountId("acc-" + UUID.randomUUID());
        account.setPlaidAccessToken(accessToken);
        account.setIsActive(true);
        return account;
    }

    private BankAccount itemAccount(UUID itemId) {
        BankAccount account = legacyAccount("access-" + itemId);
        account.setItemId(itemId);
        return account;
    }
}

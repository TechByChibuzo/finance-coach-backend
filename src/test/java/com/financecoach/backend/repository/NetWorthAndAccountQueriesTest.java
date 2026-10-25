package com.financecoach.backend.repository;

import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.Budget;
import com.financecoach.backend.model.Holding;
import com.financecoach.backend.model.ManualAsset;
import com.financecoach.backend.model.ManualLiability;
import com.financecoach.backend.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sums behind net worth and portfolio allocation, account and usage lookups, the embedding
 * delete and the per-user deletes account deletion runs, against real Postgres.
 */
class NetWorthAndAccountQueriesTest extends PostgresRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private ManualAssetRepository assetRepository;
    @Autowired private ManualLiabilityRepository liabilityRepository;
    @Autowired private BankAccountRepository bankAccountRepository;
    @Autowired private UsageTrackingRepository usageRepository;
    @Autowired private TransactionEmbeddingRepository embeddingRepository;
    @Autowired private BudgetRepository budgetRepository;

    private UUID jane;
    private UUID other;

    @BeforeEach
    void users() {
        jane = userRepository.saveAndFlush(new User("jane@example.com", "hash", "Jane")).getId();
        other = userRepository.saveAndFlush(new User("other@example.com", "hash", "Other")).getId();
    }

    @Test
    void assetAllocation_sumsHoldingValuesByType_forTheUser() {
        UUID account = account(jane, "investment", true).getId();
        holding(jane, account, "sec-aapl", "stock", "1500.00");
        holding(jane, account, "sec-msft", "stock", "500.00");
        holding(jane, account, "sec-vti", "etf", "2000.00");
        holding(other, account(other, "investment", true).getId(), "sec-aapl", "stock", "9999.00");

        Map<String, BigDecimal> allocation = holdingRepository.getAssetAllocationByUserId(jane).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (BigDecimal) row[1]));

        assertThat(allocation).hasSize(2);
        assertThat(allocation.get("stock")).isEqualByComparingTo("2000.00");
        assertThat(allocation.get("etf")).isEqualByComparingTo("2000.00");
    }

    @Test
    void manualAssetAndLiabilityTotals_areTheUsersSums_andZeroWhenThereAreNone() {
        assetRepository.saveAndFlush(ManualAsset.builder().userId(jane).name("House").type("real_estate")
                .value(new BigDecimal("300000.00")).build());
        assetRepository.saveAndFlush(ManualAsset.builder().userId(jane).name("Car").type("vehicle")
                .value(new BigDecimal("15000.00")).build());
        assetRepository.saveAndFlush(ManualAsset.builder().userId(other).name("Boat").type("other")
                .value(new BigDecimal("50000.00")).build());
        liabilityRepository.saveAndFlush(ManualLiability.builder().userId(jane).name("Mortgage").type("mortgage")
                .balance(new BigDecimal("200000.00")).build());

        assertThat(assetRepository.getTotalValueByUserId(jane)).isEqualByComparingTo("315000.00");
        assertThat(liabilityRepository.getTotalBalanceByUserId(jane)).isEqualByComparingTo("200000.00");
        assertThat(liabilityRepository.getTotalBalanceByUserId(other)).isEqualByComparingTo("0");
    }

    @Test
    void usersWithInvestmentAccounts_countsOnlyActiveInvestmentAccounts() {
        account(jane, "investment", true);
        account(jane, "investment", true);
        account(other, "investment", false);
        account(other, "depository", true);

        assertThat(bankAccountRepository.findDistinctUserIdsWithInvestmentAccounts()).containsExactly(jane);
    }

    @Test
    void updateLastSyncedAt_touchesOnlyThatColumn_soRefreshedBalancesSurvive() {
        BankAccount account = account(jane, "depository", true);
        // A balance refresh lands while the sync is finishing
        jdbcTemplate.update("UPDATE bank_accounts SET current_balance = 1234.56 WHERE id = ?", account.getId());

        LocalDateTime synced = LocalDateTime.of(2026, 10, 25, 6, 0);
        assertThat(bankAccountRepository.updateLastSyncedAt(List.of(account.getId()), synced)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT current_balance FROM bank_accounts WHERE id = ?",
                BigDecimal.class, account.getId())).isEqualByComparingTo("1234.56");
        assertThat(jdbcTemplate.queryForObject("SELECT last_synced_at FROM bank_accounts WHERE id = ?",
                LocalDateTime.class, account.getId())).isEqualTo(synced);
    }

    @Test
    void usageForAPeriod_isFoundByAnyDateInsideIt() {
        usageRepository.reserveUsage(jane, "ai_coach", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 5);

        assertThat(usageRepository.findByUserIdAndFeatureNameAndPeriod(jane, "ai_coach",
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15)))
                .hasValueSatisfying(u -> assertThat(u.getUsageCount()).isEqualTo(1));
        assertThat(usageRepository.findByUserIdAndFeatureNameAndPeriod(jane, "ai_coach",
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2))).isEmpty();
    }

    @Test
    void deleteEmbeddingByTransactionId_removesJustThatOne_withoutLoadingTheVectorColumn() {
        jdbcTemplate.update("INSERT INTO transactions (id, user_id, account_id, plaid_transaction_id, date, amount) "
                + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'tx-1', '2026-09-01', 5), "
                + "(gen_random_uuid(), ?, gen_random_uuid(), 'tx-2', '2026-09-01', 5)", jane, jane);
        String vector = Arrays.toString(new float[1536]);
        for (String tx : new String[] {"tx-1", "tx-2"}) {
            jdbcTemplate.update("INSERT INTO transaction_embeddings (id, transaction_id, user_id, embedding, content, created_at) "
                    + "VALUES (gen_random_uuid(), ?, ?, ?::vector, 'text', NOW())", tx, jane, vector);
        }

        assertThat(embeddingRepository.deleteByTransactionId("tx-1")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT transaction_id FROM transaction_embeddings", String.class))
                .containsExactly("tx-2");
    }

    @Test
    void accountDeletionsPerUserDeletes_leaveOtherUsersRowsAlone() {
        budgetRepository.saveAndFlush(new Budget(jane, "Food", LocalDate.of(2026, 10, 1), new BigDecimal("400")));
        budgetRepository.saveAndFlush(new Budget(other, "Food", LocalDate.of(2026, 10, 1), new BigDecimal("400")));
        assetRepository.saveAndFlush(ManualAsset.builder().userId(jane).name("Car").type("vehicle").value(BigDecimal.TEN).build());
        assetRepository.saveAndFlush(ManualAsset.builder().userId(other).name("Car").type("vehicle").value(BigDecimal.TEN).build());
        usageRepository.reserveUsage(jane, "ai_coach", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 5);
        usageRepository.reserveUsage(other, "ai_coach", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 5);

        budgetRepository.deleteAllByUserId(jane);
        assetRepository.deleteAllByUserId(jane);
        usageRepository.deleteAllByUserId(jane);

        for (String table : new String[] {"budgets", "manual_assets", "usage_tracking"}) {
            assertThat(jdbcTemplate.queryForList("SELECT user_id FROM " + table, UUID.class))
                    .as(table).containsExactly(other);
        }
    }

    private void holding(UUID userId, UUID accountId, String securityId, String type, String value) {
        holdingRepository.saveAndFlush(Holding.builder().userId(userId).accountId(accountId).securityId(securityId)
                .name(securityId).type(type).quantity(BigDecimal.ONE).currentPrice(new BigDecimal(value))
                .currentValue(new BigDecimal(value)).build());
    }

    private BankAccount account(UUID userId, String type, boolean active) {
        BankAccount account = new BankAccount();
        account.setUserId(userId);
        account.setPlaidAccountId("acc-" + UUID.randomUUID());
        account.setPlaidAccessToken("access");
        account.setAccountType(type);
        account.setIsActive(active);
        return bankAccountRepository.saveAndFlush(account);
    }
}

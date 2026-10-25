package com.financecoach.backend.repository;

import com.financecoach.backend.model.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    List<BankAccount> findByUserId(UUID userId);

    List<BankAccount> findByUserIdAndIsActive(UUID userId, Boolean isActive);

    List<BankAccount> findByIsActive(Boolean isActive);

    List<BankAccount> findByItemIdAndIsActive(UUID itemId, Boolean isActive);

    // Bank connections (Plaid items) with at least one active account; plan limits count these
    @Query("SELECT COUNT(DISTINCT a.itemId) FROM BankAccount a "
            + "WHERE a.userId = :userId AND a.isActive = true AND a.itemId IS NOT NULL")
    long countConnectedItems(@Param("userId") UUID userId);

    // Active connections linked before items were stored (no itemId): one per access token.
    // Counted after decryption: tokens are salted, so SQL sees a different value on every account.
    default long countLegacyConnections(UUID userId) {
        return findByUserIdAndIsActive(userId, true).stream()
                .filter(a -> a.getItemId() == null)
                .map(BankAccount::getPlaidAccessToken)
                .distinct()
                .count();
    }

    default long countBankConnections(UUID userId) {
        return countConnectedItems(userId) + countLegacyConnections(userId);
    }

    // Touches only last_synced_at, so it can't overwrite balances refreshed in the meantime
    @Transactional
    @Modifying
    @Query("UPDATE BankAccount a SET a.lastSyncedAt = :syncedAt WHERE a.id IN :ids")
    int updateLastSyncedAt(@Param("ids") Collection<UUID> ids, @Param("syncedAt") LocalDateTime syncedAt);

    Optional<BankAccount> findByPlaidAccountId(String plaidAccountId);


    // Find investment accounts
    List<BankAccount> findByUserIdAndAccountTypeAndIsActive(UUID userId, String accountType, Boolean isActive);

    // Find accounts by type list
    List<BankAccount> findByUserIdAndAccountTypeInAndIsActive(UUID userId, List<String> accountTypes, Boolean isActive);

    // Get distinct user IDs with investment accounts
    @Query("SELECT DISTINCT ba.userId FROM BankAccount ba WHERE ba.accountType = 'investment' AND ba.isActive = true")
    List<UUID> findDistinctUserIdsWithInvestmentAccounts();

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM BankAccount b WHERE b.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

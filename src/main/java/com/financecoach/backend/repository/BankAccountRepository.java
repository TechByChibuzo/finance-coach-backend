// src/main/java/com/financecoach/userservice/repository/BankAccountRepository.java
package com.financecoach.backend.repository;

import com.financecoach.backend.model.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    List<BankAccount> findByUserId(UUID userId);

    List<BankAccount> findByUserIdAndIsActive(UUID userId, Boolean isActive);

    List<BankAccount> findByIsActive(Boolean isActive);

    Optional<BankAccount> findByPlaidAccountId(String plaidAccountId);

    boolean existsByPlaidAccountId(String plaidAccountId);

    // Find investment accounts
    List<BankAccount> findByUserIdAndAccountTypeAndIsActive(UUID userId, String accountType, Boolean isActive);

    // Find accounts by type list
    List<BankAccount> findByUserIdAndAccountTypeInAndIsActive(UUID userId, List<String> accountTypes, Boolean isActive);

    // Get distinct user IDs with investment accounts
    @Query("SELECT DISTINCT ba.userId FROM BankAccount ba WHERE ba.accountType = 'investment' AND ba.isActive = true")
    List<UUID> findDistinctUserIdsWithInvestmentAccounts();

}
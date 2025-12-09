// src/main/java/com/financecoach/userservice/repository/BankAccountRepository.java
package com.financecoach.userservice.repository;

import com.financecoach.userservice.model.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
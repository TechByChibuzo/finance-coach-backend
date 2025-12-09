// src/main/java/com/financecoach/userservice/service/TransactionSyncScheduler.java
package com.financecoach.userservice.service;

import com.financecoach.userservice.model.BankAccount;
import com.financecoach.userservice.repository.BankAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionSyncScheduler {

    private final TransactionService transactionService;
    private final BankAccountRepository bankAccountRepository;

    @Autowired
    public TransactionSyncScheduler(TransactionService transactionService,
                                    BankAccountRepository bankAccountRepository) {
        this.transactionService = transactionService;
        this.bankAccountRepository = bankAccountRepository;
    }

    /**
     * Sync transactions for all active accounts every 12 hours
     * Runs at 6 AM and 6 PM every day
     */
    @Scheduled(cron = "0 0 6,18 * * *")
    public void syncAllAccounts() {
        System.out.println("Starting scheduled transaction sync at " + LocalDateTime.now());

        List<BankAccount> activeAccounts = bankAccountRepository.findByIsActive(true);

        int successCount = 0;
        int failureCount = 0;

        for (BankAccount account : activeAccounts) {
            try {
                transactionService.syncTransactions(account.getId(), account.getUserId());
                successCount++;
                System.out.println("Successfully synced account: " + account.getId());
            } catch (Exception e) {
                failureCount++;
                System.err.println("Failed to sync account " + account.getId() + ": " + e.getMessage());
                // In production: log to monitoring service, send alert, etc.
            }
        }

        System.out.println("Sync completed. Success: " + successCount + ", Failed: " + failureCount);
    }

    /**
     * Alternative: Sync every 12 hours (simple version)
     * Runs every 12 hours starting from app startup
     */
    // @Scheduled(fixedRate = 43200000) // 12 hours in milliseconds
    // public void syncAllAccountsFixedRate() {
    //     syncAllAccounts();
    // }
}
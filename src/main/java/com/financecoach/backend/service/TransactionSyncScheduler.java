// src/main/java/com/financecoach/userservice/service/TransactionSyncScheduler.java
package com.financecoach.backend.service;

import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.repository.BankAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionSyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(TransactionSyncScheduler.class);

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
        logger.info("Starting scheduled transaction sync");

        List<BankAccount> activeAccounts = bankAccountRepository.findByIsActive(true);

        int successCount = 0;
        int failureCount = 0;

        for (BankAccount account : activeAccounts) {
            try {
                transactionService.syncTransactions(account.getId(), account.getUserId());
                successCount++;
                logger.info("Successfully synced account: {}", account.getId());
            } catch (Exception e) {
                failureCount++;
                logger.error("Failed to sync account {}: {}", account.getId(), e.getMessage());
            }
        }

        logger.info("Sync completed. Success: {}, Failed: {}", successCount, failureCount);
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
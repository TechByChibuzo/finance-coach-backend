// src/main/java/com/financecoach/userservice/service/TransactionService.java
package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.PlaidItemRepository;
import com.financecoach.backend.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);
    private static final int SYNC_PAGE_SIZE = 500;
    private static final int MAX_SYNC_ATTEMPTS = 3;

    private final PlaidApi plaidClient;
    private final TransactionRepository transactionRepository;
    private final BankAccountRepository bankAccountRepository;
    @Autowired
    private MetricsService metricsService;
    private final VectorStoreService vectorStoreService;
    private final PlaidService plaidService;
    private final PlaidItemRepository plaidItemRepository;

    @Autowired
    public TransactionService(PlaidApi plaidClient,
                              TransactionRepository transactionRepository,
                              BankAccountRepository bankAccountRepository,
                              VectorStoreService vectorStoreService,
                              PlaidService plaidService,
                              PlaidItemRepository plaidItemRepository) {
        this.plaidClient = plaidClient;
        this.transactionRepository = transactionRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.vectorStoreService = vectorStoreService;
        this.plaidService = plaidService;
        this.plaidItemRepository = plaidItemRepository;
    }

    /**
     * Sync transactions for a specific bank account.
     * Plaid returns transactions per item (bank login), so this syncs the account's whole item
     * and returns the new transactions for this account.
     */
    public List<Transaction> syncTransactions(UUID accountId, UUID userId) {
        BankAccount bankAccount = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new BankAccountNotFoundException(accountId));

        // Verify ownership
        if (!bankAccount.getUserId().equals(userId)) {
            throw new UnauthorizedAccessException("bank account");
        }

        return syncItem(plaidService.getItem(bankAccount)).stream()
                .filter(t -> t.getAccountId().equals(accountId))
                .toList();
    }

    /**
     * Sync transactions for all user's bank accounts
     */
    public List<Transaction> syncAllTransactions(UUID userId) {
        List<BankAccount> accounts = bankAccountRepository.findByUserIdAndIsActive(userId, true);

        if (accounts.isEmpty()) {
            throw new BankAccountNotFoundException("No active bank accounts found");
        }

        List<Transaction> allTransactions = new ArrayList<>();
        for (PlaidItem item : plaidService.getItems(accounts)) {
            allTransactions.addAll(syncItem(item));
        }

        return allTransactions;
    }

    /**
     * Sync one Plaid item using /transactions/sync. Starting from the item's stored cursor,
     * Plaid returns everything added, modified or removed since the last sync (the first sync
     * returns the full available history). Each transaction is assigned to the account Plaid
     * reports for it. Returns the newly added transactions.
     */
    public List<Transaction> syncItem(PlaidItem item) {
        long startTime = System.currentTimeMillis();

        Map<String, BankAccount> accountsByPlaidId = plaidService.getActiveAccountsByPlaidId(item);
        if (accountsByPlaidId.isEmpty()) {
            return List.of();
        }

        SyncChanges changes = fetchChanges(item);

        // Save transactions; embeddings for new and changed ones are built afterwards, in the background
        List<Transaction> savedTransactions = new ArrayList<>();
        List<Transaction> toIndex = new ArrayList<>();
        for (com.plaid.client.model.Transaction plaidTx : changes.added()) {
            BankAccount bankAccount = accountsByPlaidId.get(plaidTx.getAccountId());
            if (bankAccount == null) {
                continue; // account was disconnected or never linked
            }
            if (transactionRepository.existsByPlaidTransactionId(plaidTx.getTransactionId())) {
                continue;
            }

            Transaction transaction = convertPlaidTransaction(plaidTx, bankAccount);
            Transaction saved = transactionRepository.save(transaction);
            savedTransactions.add(saved);
            toIndex.add(saved);
        }

        // Update changed transactions (e.g. amount or merchant corrected) and re-index them
        for (com.plaid.client.model.Transaction plaidTx : changes.modified()) {
            BankAccount bankAccount = accountsByPlaidId.get(plaidTx.getAccountId());
            if (bankAccount == null) {
                continue;
            }
            transactionRepository.findByPlaidTransactionId(plaidTx.getTransactionId()).ifPresent(existing -> {
                applyPlaidFields(existing, plaidTx, bankAccount);
                Transaction saved = transactionRepository.save(existing);
                vectorStoreService.removeTransaction(saved.getPlaidTransactionId());
                toIndex.add(saved);
            });
        }

        // Remove deleted transactions - including pending ones replaced by their posted version
        for (RemovedTransaction removed : changes.removed()) {
            transactionRepository.findByPlaidTransactionId(removed.getTransactionId()).ifPresent(existing -> {
                transactionRepository.delete(existing);
                vectorStoreService.removeTransaction(existing.getPlaidTransactionId());
            });
        }

        // Only advance the cursor once every change is applied, so a failure replays them next time
        item.setTransactionsCursor(changes.nextCursor());
        plaidItemRepository.save(item);

        // Embed for the AI coach without making the user wait on the embedding API
        if (!toIndex.isEmpty()) {
            vectorStoreService.indexTransactionsAsync(toIndex);
        }

        // Keep balances current for cash, credit and net worth figures
        try {
            plaidService.refreshBalances(item);
        } catch (PlaidIntegrationException e) {
            logger.warn("Transactions synced but balance refresh failed for item {}: {}",
                    item.getId(), e.getMessage());
        }

        // Track metrics
        metricsService.recordTransactionsSynced(savedTransactions.size());
        long duration = System.currentTimeMillis() - startTime;
        metricsService.recordTransactionSyncDuration(duration);

        // Update last synced time
        for (BankAccount bankAccount : accountsByPlaidId.values()) {
            bankAccount.setLastSyncedAt(LocalDateTime.now());
            bankAccountRepository.save(bankAccount);
        }

        logger.info("Synced item {} - added: {}, modified: {}, removed: {}",
                item.getId(), savedTransactions.size(), changes.modified().size(), changes.removed().size());

        return savedTransactions;
    }

    /**
     * Page through /transactions/sync until has_more is false. If the data changes while
     * paginating, Plaid asks us to restart from the original cursor.
     */
    private SyncChanges fetchChanges(PlaidItem item) {
        for (int attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt++) {
            List<com.plaid.client.model.Transaction> added = new ArrayList<>();
            List<com.plaid.client.model.Transaction> modified = new ArrayList<>();
            List<RemovedTransaction> removed = new ArrayList<>();
            String cursor = item.getTransactionsCursor();
            boolean hasMore = true;
            boolean restart = false;

            try {
                while (hasMore) {
                    TransactionsSyncRequest request = new TransactionsSyncRequest()
                            .accessToken(item.getAccessToken())
                            .count(SYNC_PAGE_SIZE);
                    if (cursor != null) {
                        request.cursor(cursor);
                    }

                    Response<TransactionsSyncResponse> response = plaidClient
                            .transactionsSync(request)
                            .execute();

                    if (!response.isSuccessful() || response.body() == null) {
                        String errorMsg = response.errorBody() != null
                                ? response.errorBody().string()
                                : "Unknown error";
                        if (errorMsg.contains("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION")) {
                            restart = true;
                            break;
                        }
                        throw new PlaidIntegrationException("Failed to fetch transactions: " + errorMsg);
                    }

                    TransactionsSyncResponse body = response.body();
                    added.addAll(body.getAdded());
                    modified.addAll(body.getModified());
                    removed.addAll(body.getRemoved());
                    cursor = body.getNextCursor();
                    hasMore = Boolean.TRUE.equals(body.getHasMore());
                }
            } catch (IOException e) {
                throw new PlaidIntegrationException("Network error syncing transactions", e);
            }

            if (!restart) {
                return new SyncChanges(added, modified, removed, cursor);
            }
            logger.warn("Transactions changed during pagination for item {}, restarting (attempt {})",
                    item.getId(), attempt);
        }

        throw new PlaidIntegrationException("Transactions kept changing during sync, try again later");
    }

    private record SyncChanges(List<com.plaid.client.model.Transaction> added,
                               List<com.plaid.client.model.Transaction> modified,
                               List<RemovedTransaction> removed,
                               String nextCursor) {}

    /**
     * Get transactions for a user with optional filters
     */
    public List<Transaction> getTransactions(UUID userId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate);
        }
        return transactionRepository.findByUserId(userId);
    }

    /**
     * Get transactions by category
     */
    public List<Transaction> getTransactionsByCategory(UUID userId, String category) {
        return transactionRepository.findByUserIdAndCategory(userId, category);
    }

    /**
     * Convert Plaid transaction to our Transaction entity
     */
    private Transaction convertPlaidTransaction(com.plaid.client.model.Transaction plaidTx,
                                                BankAccount bankAccount) {
        Transaction transaction = new Transaction();
        transaction.setPlaidTransactionId(plaidTx.getTransactionId());
        applyPlaidFields(transaction, plaidTx, bankAccount);
        return transaction;
    }

    /**
     * Copy Plaid's fields onto our Transaction entity
     */
    private void applyPlaidFields(Transaction transaction, com.plaid.client.model.Transaction plaidTx,
                                  BankAccount bankAccount) {
        transaction.setUserId(bankAccount.getUserId());
        transaction.setAccountId(bankAccount.getId());

        // Date
        transaction.setDate(plaidTx.getDate());

        // Amount (Plaid uses positive for expenses, negative for income)
        transaction.setAmount(BigDecimal.valueOf(plaidTx.getAmount()));

        // Merchant
        transaction.setMerchantName(plaidTx.getMerchantName());
        transaction.setName(plaidTx.getName());

        // Category
        if (plaidTx.getPersonalFinanceCategory() != null) {
            transaction.setCategory(plaidTx.getPersonalFinanceCategory().getPrimary());
            transaction.setSubcategory(plaidTx.getPersonalFinanceCategory().getDetailed());
        } else if (plaidTx.getCategory() != null && !plaidTx.getCategory().isEmpty()) {
            // Fallback to old category system
            transaction.setCategory(plaidTx.getCategory().get(0));
            if (plaidTx.getCategory().size() > 1) {
                transaction.setSubcategory(plaidTx.getCategory().get(1));
            }
        }

        // Other fields
        if (plaidTx.getPaymentChannel() != null) {
            transaction.setPaymentChannel(plaidTx.getPaymentChannel().toString());
        }
        transaction.setPending(plaidTx.getPending());
        transaction.setCurrencyCode(plaidTx.getIsoCurrencyCode());

        // Location
        if (plaidTx.getLocation() != null) {
            transaction.setLocationAddress(plaidTx.getLocation().getAddress());
            transaction.setLocationCity(plaidTx.getLocation().getCity());
            transaction.setLocationRegion(plaidTx.getLocation().getRegion());
        }
    }
}
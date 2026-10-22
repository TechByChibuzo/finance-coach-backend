// src/main/java/com/financecoach/userservice/service/TransactionService.java
package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.BankReconnectRequiredException;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);
    private static final int SYNC_PAGE_SIZE = 500;
    private static final int MAX_SYNC_ATTEMPTS = 3;
    private static final int EMBEDDING_BACKFILL_LIMIT = 500;
    private static final int LOGO_BACKFILL_CHUNK = 1000;

    private final PlaidApi plaidClient;
    private final TransactionRepository transactionRepository;
    private final BankAccountRepository bankAccountRepository;
    @Autowired
    private MetricsService metricsService;
    @Autowired
    private CategoryRuleService categoryRuleService;
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
            try {
                allTransactions.addAll(syncItem(item));
            } catch (BankReconnectRequiredException e) {
                // The user is asked to reconnect this bank; keep syncing the others
                logger.info("Skipped item {}: the bank needs the user to sign in again", item.getId());
            }
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

        // A first sync brings every transaction with its logo; later ones only bring changes
        boolean firstSync = item.getTransactionsCursor() == null;
        SyncChanges changes;
        try {
            changes = fetchChanges(item, item.getTransactionsCursor());
        } catch (BankReconnectRequiredException e) {
            if (!Boolean.TRUE.equals(item.getNeedsReconnect())) {
                item.setNeedsReconnect(true);
                plaidItemRepository.save(item);
            }
            throw e;
        }

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
            categoryRuleService.applyRules(bankAccount.getUserId(), List.of(transaction));
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
                // Keep a category the user chose over Plaid's
                String userCategory = existing.getCategory();
                String userSubcategory = existing.getSubcategory();
                BigDecimal previousAmount = existing.getAmount();
                applyPlaidFields(existing, plaidTx, bankAccount);
                if (Boolean.TRUE.equals(existing.getCategoryOverridden())) {
                    existing.setCategory(userCategory);
                    existing.setSubcategory(userSubcategory);
                }
                // The bank changed the amount of a transaction the user split: its parts no longer
                // add up, so undo the split rather than count the wrong amounts
                if (Boolean.TRUE.equals(existing.getSplit()) && existing.getAmount().compareTo(previousAmount) != 0) {
                    transactionRepository.deleteBySplitFromId(existing.getId());
                    existing.setSplit(false);
                }
                Transaction saved = transactionRepository.save(existing);
                vectorStoreService.removeTransaction(saved.getPlaidTransactionId());
                if (!TransactionFilters.isExcluded(saved)) {
                    toIndex.add(saved);
                }
            });
        }

        // Remove deleted transactions - including pending ones replaced by their posted version
        for (RemovedTransaction removed : changes.removed()) {
            transactionRepository.findByPlaidTransactionId(removed.getTransactionId()).ifPresent(existing -> {
                if (Boolean.TRUE.equals(existing.getSplit())) {
                    transactionRepository.deleteBySplitFromId(existing.getId());
                }
                transactionRepository.delete(existing);
                vectorStoreService.removeTransaction(existing.getPlaidTransactionId());
            });
        }

        // Only advance the cursor once every change is applied, so a failure replays them next time
        item.setTransactionsCursor(changes.nextCursor());
        if (Boolean.TRUE.equals(item.getNeedsReconnect())) {
            item.setNeedsReconnect(false); // the bank is sharing data again
        }
        if (firstSync) {
            item.setLogosBackfilled(true);
        }
        plaidItemRepository.save(item);

        if (!Boolean.TRUE.equals(item.getLogosBackfilled())) {
            backfillLogos(item);
        }

        // Embed for the AI coach without making the user wait on the embedding API.
        // Skip anything Plaid also removed in this sync (e.g. modified, then removed).
        Set<String> removedIds = changes.removed().stream()
                .map(RemovedTransaction::getTransactionId)
                .collect(Collectors.toSet());
        List<Transaction> toEmbed = new ArrayList<>(toIndex.stream()
                .filter(t -> !removedIds.contains(t.getPlaidTransactionId()))
                .toList());

        // Also retry earlier transactions that still have no embedding (a batch failed, e.g. during
        // an OpenAI outage). Capped per sync; a large backlog drains over the next syncs.
        Set<String> queued = toEmbed.stream().map(Transaction::getPlaidTransactionId)
                .collect(Collectors.toCollection(HashSet::new));
        List<UUID> accountIds = accountsByPlaidId.values().stream().map(BankAccount::getId).toList();
        for (Transaction missing : transactionRepository.findWithoutEmbedding(accountIds, PageRequest.of(0, EMBEDDING_BACKFILL_LIMIT))) {
            if (queued.add(missing.getPlaidTransactionId())) {
                toEmbed.add(missing);
            }
        }

        if (!toEmbed.isEmpty()) {
            vectorStoreService.indexTransactionsAsync(toEmbed);
        }

        // Keep balances current for cash, credit and net worth figures
        try {
            plaidService.refreshBalances(item);
        } catch (PlaidIntegrationException e) {
            logger.warn("Transactions synced but balance refresh failed for item {}: {}",
                    item.getId(), e.getMessage());
        }

        // The refresh fills in account masks, which is how a card linked twice is recognised
        removeDuplicateAccounts(item.getUserId());

        // Track metrics
        metricsService.recordTransactionsSynced(savedTransactions.size());
        long duration = System.currentTimeMillis() - startTime;
        metricsService.recordTransactionSyncDuration(duration);

        // Update last synced time with a targeted update. These account objects were loaded before
        // refreshBalances saved new balances; saving them would write the old balances back.
        bankAccountRepository.updateLastSyncedAt(
                accountsByPlaidId.values().stream().map(BankAccount::getId).toList(), LocalDateTime.now());

        logger.info("Synced item {} - added: {}, modified: {}, removed: {}",
                item.getId(), savedTransactions.size(), changes.modified().size(), changes.removed().size());

        return savedTransactions;
    }

    /**
     * Keeps the oldest connection of a card linked twice and drops the newer copy with the
     * transactions it brought in, so spending isn't counted twice
     */
    void removeDuplicateAccounts(UUID userId) {
        for (BankAccount duplicate : plaidService.findDuplicateAccounts(userId)) {
            int removed = transactionRepository.deleteByAccountId(duplicate.getId());
            plaidService.retireDuplicateAccount(duplicate);
            logger.info("Removed account {} of user {}: already connected through another bank login ({} duplicate transactions)",
                    duplicate.getId(), userId, removed);
        }
    }

    /**
     * Page through /transactions/sync until has_more is false. If the data changes while
     * paginating, Plaid asks us to restart from the original cursor.
     */
    // Plaid errors that only the user can fix, by signing in to the bank again through Link
    private static final Set<String> RECONNECT_ERRORS =
            Set.of("ITEM_LOGIN_REQUIRED", "PENDING_EXPIRATION", "PENDING_DISCONNECT");

    private SyncChanges fetchChanges(PlaidItem item, String startCursor) {
        for (int attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt++) {
            List<com.plaid.client.model.Transaction> added = new ArrayList<>();
            List<com.plaid.client.model.Transaction> modified = new ArrayList<>();
            List<RemovedTransaction> removed = new ArrayList<>();
            String cursor = startCursor;
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
                        if (RECONNECT_ERRORS.stream().anyMatch(errorMsg::contains)) {
                            throw new BankReconnectRequiredException("The bank needs the user to sign in again");
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
     * The most recent transactions, newest first
     */
    public List<Transaction> getRecentTransactions(UUID userId, int limit) {
        return transactionRepository.findByUserIdOrderByDateDescIdDesc(userId, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
    }

    /**
     * Transactions across all months whose merchant name or description contains the text
     */
    public List<Transaction> searchTransactions(UUID userId, String text, int limit) {
        String query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return List.of();
        }
        // Escape LIKE wildcards so the user's text is matched literally
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return transactionRepository.searchByUserId(userId, pattern, PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
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
    /**
     * Transactions synced before logos were stored don't have them. Replays the item's history from
     * Plaid once (without touching the sync cursor) and fills in logos and websites that are missing.
     * A failure is logged and retried on the next sync, never failing the sync itself.
     */
    private void backfillLogos(PlaidItem item) {
        try {
            Map<String, com.plaid.client.model.Transaction> withLogos = new HashMap<>();
            for (com.plaid.client.model.Transaction plaidTx : fetchChanges(item, null).added()) {
                if (logoUrl(plaidTx) != null || merchantWebsite(plaidTx) != null) {
                    withLogos.put(plaidTx.getTransactionId(), plaidTx);
                }
            }

            List<Transaction> updated = new ArrayList<>();
            List<String> ids = new ArrayList<>(withLogos.keySet());
            for (int start = 0; start < ids.size(); start += LOGO_BACKFILL_CHUNK) {
                for (Transaction existing : transactionRepository.findByPlaidTransactionIdIn(
                        ids.subList(start, Math.min(start + LOGO_BACKFILL_CHUNK, ids.size())))) {
                    if (existing.getLogoUrl() == null && existing.getMerchantWebsite() == null) {
                        com.plaid.client.model.Transaction plaidTx = withLogos.get(existing.getPlaidTransactionId());
                        existing.setLogoUrl(logoUrl(plaidTx));
                        existing.setMerchantWebsite(merchantWebsite(plaidTx));
                        updated.add(existing);
                    }
                }
            }
            transactionRepository.saveAll(updated);

            item.setLogosBackfilled(true);
            plaidItemRepository.save(item);
            logger.info("Filled in merchant logos for {} transactions of item {}", updated.size(), item.getId());
        } catch (Exception e) {
            logger.warn("Couldn't fill in merchant logos for item {}, will retry next sync: {}",
                    item.getId(), e.getMessage());
        }
    }

    // Plaid puts the merchant's logo on the transaction, or on its merchant counterparty
    static String logoUrl(com.plaid.client.model.Transaction plaidTx) {
        if (plaidTx.getLogoUrl() != null) {
            return plaidTx.getLogoUrl();
        }
        return plaidTx.getCounterparties() == null ? null : plaidTx.getCounterparties().stream()
                .map(TransactionCounterparty::getLogoUrl)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    static String merchantWebsite(com.plaid.client.model.Transaction plaidTx) {
        if (plaidTx.getWebsite() != null) {
            return plaidTx.getWebsite();
        }
        return plaidTx.getCounterparties() == null ? null : plaidTx.getCounterparties().stream()
                .map(TransactionCounterparty::getWebsite)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

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
        transaction.setLogoUrl(logoUrl(plaidTx));
        transaction.setMerchantWebsite(merchantWebsite(plaidTx));

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
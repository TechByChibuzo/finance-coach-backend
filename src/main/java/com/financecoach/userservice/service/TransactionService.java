// src/main/java/com/financecoach/userservice/service/TransactionService.java
package com.financecoach.userservice.service;

import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import com.financecoach.userservice.model.BankAccount;
import com.financecoach.userservice.model.Transaction;
import com.financecoach.userservice.repository.BankAccountRepository;
import com.financecoach.userservice.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final PlaidApi plaidClient;
    private final TransactionRepository transactionRepository;
    private final BankAccountRepository bankAccountRepository;

    @Autowired
    public TransactionService(PlaidApi plaidClient,
                              TransactionRepository transactionRepository,
                              BankAccountRepository bankAccountRepository) {
        this.plaidClient = plaidClient;
        this.transactionRepository = transactionRepository;
        this.bankAccountRepository = bankAccountRepository;
    }

    /**
     * Sync transactions for a specific bank account
     */
    public List<Transaction> syncTransactions(UUID accountId, UUID userId) throws IOException {
        // Get bank account
        BankAccount bankAccount = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        // Verify ownership
        if (!bankAccount.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized");
        }

        // Fetch transactions from Plaid (last 30 days)
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        TransactionsGetRequest request = new TransactionsGetRequest()
                .accessToken(bankAccount.getPlaidAccessToken())
                .startDate(startDate)
                .endDate(endDate);

        Response<TransactionsGetResponse> response = plaidClient
                .transactionsGet(request)
                .execute();

        if (!response.isSuccessful() || response.body() == null) {
            throw new RuntimeException("Failed to fetch transactions: " +
                    response.errorBody().string());
        }

        // Save transactions
        List<Transaction> savedTransactions = new ArrayList<>();
        for (com.plaid.client.model.Transaction plaidTx : response.body().getTransactions()) {
            // Check if transaction already exists
            if (transactionRepository.existsByPlaidTransactionId(plaidTx.getTransactionId())) {
                continue; // Skip duplicates
            }

            Transaction transaction = convertPlaidTransaction(plaidTx, bankAccount);
            savedTransactions.add(transactionRepository.save(transaction));
        }

        // Update last synced time
        bankAccount.setLastSyncedAt(LocalDateTime.now());
        bankAccountRepository.save(bankAccount);

        return savedTransactions;
    }

    /**
     * Sync transactions for all user's bank accounts
     */
    public List<Transaction> syncAllTransactions(UUID userId) throws IOException {
        List<BankAccount> accounts = bankAccountRepository.findByUserIdAndIsActive(userId, true);
        List<Transaction> allTransactions = new ArrayList<>();

        for (BankAccount account : accounts) {
            try {
                List<Transaction> transactions = syncTransactions(account.getId(), userId);
                allTransactions.addAll(transactions);
            } catch (Exception e) {
                // Log error but continue with other accounts
                System.err.println("Failed to sync account " + account.getId() + ": " + e.getMessage());
            }
        }

        return allTransactions;
    }

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

        transaction.setUserId(bankAccount.getUserId());
        transaction.setAccountId(bankAccount.getId());
        transaction.setPlaidTransactionId(plaidTx.getTransactionId());

        // Date
        transaction.setDate(plaidTx.getDate());

        // Amount (Plaid uses positive for expenses, negative for income)
        transaction.setAmount(plaidTx.getAmount());

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

        return transaction;
    }
}
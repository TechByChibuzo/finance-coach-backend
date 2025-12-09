// src/main/java/com/financecoach/userservice/controller/TransactionController.java
package com.financecoach.userservice.controller;

import com.financecoach.userservice.dto.plaid.TransactionResponse;
import com.financecoach.userservice.model.Transaction;
import com.financecoach.userservice.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    @Autowired
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Sync transactions for a specific account
     */
    @PostMapping("/sync/{accountId}")
    public ResponseEntity<?> syncAccountTransactions(@PathVariable UUID accountId) {
        try {
            UUID userId = getCurrentUserId();
            List<Transaction> transactions = transactionService.syncTransactions(accountId, userId);

            List<TransactionResponse> response = transactions.stream()
                    .map(this::convertToResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error syncing transactions: " + e.getMessage());
        }
    }

    /**
     * Sync transactions for all user's accounts
     */
    @PostMapping("/sync")
    public ResponseEntity<?> syncAllTransactions() {
        try {
            UUID userId = getCurrentUserId();
            List<Transaction> transactions = transactionService.syncAllTransactions(userId);

            List<TransactionResponse> response = transactions.stream()
                    .map(this::convertToResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error syncing transactions: " + e.getMessage());
        }
    }

    /**
     * Get all transactions (with optional date range)
     */
    @GetMapping
    public ResponseEntity<List<TransactionResponse>> getTransactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        List<Transaction> transactions = transactionService.getTransactions(userId, startDate, endDate);

        List<TransactionResponse> response = transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get transactions by category
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsByCategory(
            @PathVariable String category) {

        UUID userId = getCurrentUserId();
        List<Transaction> transactions = transactionService.getTransactionsByCategory(userId, category);

        List<TransactionResponse> response = transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    // Helper methods
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }

    private TransactionResponse convertToResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getDate(),
                transaction.getAmount(),
                transaction.getMerchantName(),
                transaction.getName(),
                transaction.getCategory(),
                transaction.getSubcategory(),
                transaction.getPending(),
                transaction.getCurrencyCode()
        );
    }
}
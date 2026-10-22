// src/main/java/com/financecoach/backend/controller/TransactionController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.CategoryUpdateRequest;
import com.financecoach.backend.dto.imports.TransactionImportRequest;
import com.financecoach.backend.dto.imports.TransactionImportResponse;
import com.financecoach.backend.dto.plaid.TransactionResponse;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.service.CategoryRuleService;
import com.financecoach.backend.service.TransactionImportService;
import com.financecoach.backend.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final TransactionImportService transactionImportService;
    private final CategoryRuleService categoryRuleService;

    @Autowired
    public TransactionController(TransactionService transactionService,
                                 TransactionImportService transactionImportService,
                                 CategoryRuleService categoryRuleService) {
        this.transactionService = transactionService;
        this.transactionImportService = transactionImportService;
        this.categoryRuleService = categoryRuleService;
    }

    /**
     * Change a transaction's category, optionally for every transaction from that merchant
     * PATCH /api/transactions/{id}/category
     */
    @PatchMapping("/{id}/category")
    public ResponseEntity<TransactionResponse> updateCategory(@PathVariable UUID id,
                                                              @Valid @RequestBody CategoryUpdateRequest request) {
        return ResponseEntity.ok(convertToResponse(
                categoryRuleService.updateCategory(getCurrentUserId(), id, request.category(), request.remember())));
    }

    /**
     * Import transactions from a bank's CSV export (parsed in the browser)
     * POST /api/transactions/import
     */
    @PostMapping("/import")
    public ResponseEntity<TransactionImportResponse> importTransactions(
            @Valid @RequestBody TransactionImportRequest request) {
        return ResponseEntity.ok(transactionImportService.importTransactions(getCurrentUserId(), request));
    }

    /**
     * Undo an import, removing the transactions it added
     * DELETE /api/transactions/imports/{batchId}
     */
    @DeleteMapping("/imports/{batchId}")
    public ResponseEntity<Map<String, Integer>> undoImport(@PathVariable UUID batchId) {
        return ResponseEntity.ok(Map.of("deleted", transactionImportService.undoImport(getCurrentUserId(), batchId)));
    }

    /**
     * Sync transactions for a specific account
     * POST /api/transactions/sync/{accountId}
     */
    @PostMapping("/sync/{accountId}")
    public ResponseEntity<List<TransactionResponse>> syncAccountTransactions(@PathVariable UUID accountId) {
        UUID userId = getCurrentUserId();
        List<Transaction> transactions = transactionService.syncTransactions(accountId, userId);

        List<TransactionResponse> response = transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Sync transactions for all user's accounts
     * POST /api/transactions/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<List<TransactionResponse>> syncAllTransactions() {
        UUID userId = getCurrentUserId();
        List<Transaction> transactions = transactionService.syncAllTransactions(userId);

        List<TransactionResponse> response = transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get all transactions (with optional date range)
     * GET /api/transactions?startDate=2024-01-01&endDate=2024-12-31
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
     * GET /api/transactions/category/{category}
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsByCategory(@PathVariable String category) {
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
        TransactionResponse response = new TransactionResponse(
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
        response.setImportAccountName(transaction.getImportAccountName());
        response.setLogoUrl(transaction.getLogoUrl());
        return response;
    }
}
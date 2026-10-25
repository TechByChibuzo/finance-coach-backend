// src/main/java/com/financecoach/backend/controller/TransactionController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.CategoryUpdateRequest;
import com.financecoach.backend.dto.TransactionSplitRequest;
import com.financecoach.backend.dto.TransactionUpdateRequest;
import com.financecoach.backend.dto.imports.TransactionImportRequest;
import com.financecoach.backend.dto.imports.TransactionImportResponse;
import com.financecoach.backend.dto.plaid.TransactionResponse;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.security.CurrentUser;
import com.financecoach.backend.service.CategoryRuleService;
import com.financecoach.backend.service.TransactionEditService;
import com.financecoach.backend.service.TransactionImportService;
import com.financecoach.backend.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
    private final TransactionEditService transactionEditService;

    @Autowired
    public TransactionController(TransactionService transactionService,
                                 TransactionImportService transactionImportService,
                                 CategoryRuleService categoryRuleService,
                                 TransactionEditService transactionEditService) {
        this.transactionService = transactionService;
        this.transactionImportService = transactionImportService;
        this.categoryRuleService = categoryRuleService;
        this.transactionEditService = transactionEditService;
    }

    /**
     * Change a transaction's note, or hide it from totals
     * PATCH /api/transactions/{id}
     */
    @PatchMapping("/{id}")
    public ResponseEntity<TransactionResponse> updateTransaction(@PathVariable UUID id,
                                                                 @Valid @RequestBody TransactionUpdateRequest request) {
        return ResponseEntity.ok(convertToResponse(
                transactionEditService.updateDetails(CurrentUser.id(), id, request.notes(), request.hidden())));
    }

    /**
     * The parts a transaction was split into
     * GET /api/transactions/{id}/split
     */
    @GetMapping("/{id}/split")
    public ResponseEntity<List<TransactionResponse>> getSplit(@PathVariable UUID id) {
        return ResponseEntity.ok(transactionEditService.getParts(CurrentUser.id(), id).stream()
                .map(this::convertToResponse).toList());
    }

    /**
     * Split a transaction across categories, replacing any earlier split
     * PUT /api/transactions/{id}/split
     */
    @PutMapping("/{id}/split")
    public ResponseEntity<List<TransactionResponse>> splitTransaction(@PathVariable UUID id,
                                                                      @Valid @RequestBody TransactionSplitRequest request) {
        return ResponseEntity.ok(transactionEditService.split(CurrentUser.id(), id, request.parts()).stream()
                .map(this::convertToResponse).toList());
    }

    /**
     * Put a split transaction back together
     * DELETE /api/transactions/{id}/split
     */
    @DeleteMapping("/{id}/split")
    public ResponseEntity<TransactionResponse> unsplitTransaction(@PathVariable UUID id) {
        return ResponseEntity.ok(convertToResponse(transactionEditService.unsplit(CurrentUser.id(), id)));
    }

    /**
     * Change a transaction's category, optionally for every transaction from that merchant
     * PATCH /api/transactions/{id}/category
     */
    @PatchMapping("/{id}/category")
    public ResponseEntity<TransactionResponse> updateCategory(@PathVariable UUID id,
                                                              @Valid @RequestBody CategoryUpdateRequest request) {
        return ResponseEntity.ok(convertToResponse(
                categoryRuleService.updateCategory(CurrentUser.id(), id, request.category(), request.remember())));
    }

    /**
     * Import transactions from a bank's CSV export (parsed in the browser)
     * POST /api/transactions/import
     */
    @PostMapping("/import")
    public ResponseEntity<TransactionImportResponse> importTransactions(
            @Valid @RequestBody TransactionImportRequest request) {
        return ResponseEntity.ok(transactionImportService.importTransactions(CurrentUser.id(), request));
    }

    /**
     * Undo an import, removing the transactions it added
     * DELETE /api/transactions/imports/{batchId}
     */
    @DeleteMapping("/imports/{batchId}")
    public ResponseEntity<Map<String, Integer>> undoImport(@PathVariable UUID batchId) {
        return ResponseEntity.ok(Map.of("deleted", transactionImportService.undoImport(CurrentUser.id(), batchId)));
    }

    /**
     * Sync transactions for a specific account
     * POST /api/transactions/sync/{accountId}
     */
    @PostMapping("/sync/{accountId}")
    public ResponseEntity<List<TransactionResponse>> syncAccountTransactions(@PathVariable UUID accountId) {
        UUID userId = CurrentUser.id();
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
        UUID userId = CurrentUser.id();
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

        UUID userId = CurrentUser.id();
        List<Transaction> transactions = transactionService.getTransactions(userId, startDate, endDate);

        List<TransactionResponse> response = transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Newest transactions first
     * GET /api/transactions/recent?limit=6
     */
    @GetMapping("/recent")
    public ResponseEntity<List<TransactionResponse>> getRecentTransactions(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(transactionService.getRecentTransactions(CurrentUser.id(), limit).stream()
                .map(this::convertToResponse).toList());
    }

    /**
     * Search all transactions by merchant or description
     * GET /api/transactions/search?q=costco
     */
    @GetMapping("/search")
    public ResponseEntity<List<TransactionResponse>> searchTransactions(@RequestParam String q,
                                                                         @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(transactionService.searchTransactions(CurrentUser.id(), q, limit).stream()
                .map(this::convertToResponse).toList());
    }

    /**
     * Get transactions by category
     * GET /api/transactions/category/{category}
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsByCategory(@PathVariable String category) {
        UUID userId = CurrentUser.id();
        List<Transaction> transactions = transactionService.getTransactionsByCategory(userId, category);

        List<TransactionResponse> response = transactions.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
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
        response.setNotes(transaction.getNotes());
        response.setHidden(Boolean.TRUE.equals(transaction.getHidden()));
        response.setSplit(Boolean.TRUE.equals(transaction.getSplit()));
        response.setSplitFromId(transaction.getSplitFromId());
        return response;
    }
}
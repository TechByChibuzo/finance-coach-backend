// src/main/java/com/financecoach/userservice/controller/BudgetController.java
package com.financecoach.userservice.controller;

import com.financecoach.userservice.dto.BudgetRequest;
import com.financecoach.userservice.dto.BudgetResponse;
import com.financecoach.userservice.dto.BudgetSummaryResponse;
import com.financecoach.userservice.service.BudgetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    @Autowired
    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /**
     * Create or update a budget
     * POST /api/budgets
     */
    @PostMapping
    public ResponseEntity<?> createOrUpdateBudget(@RequestBody BudgetRequest request) {
        try {
            UUID userId = getCurrentUserId();
            BudgetResponse budget = budgetService.createOrUpdateBudget(userId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(budget);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get current month's budget summary
     * GET /api/budgets/current
     */
    @GetMapping("/current")
    public ResponseEntity<BudgetSummaryResponse> getCurrentMonthBudgets() {
        UUID userId = getCurrentUserId();
        BudgetSummaryResponse summary = budgetService.getCurrentMonthBudgets(userId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Get budget summary for a specific month
     * GET /api/budgets?month=2024-12-01
     */
    @GetMapping
    public ResponseEntity<BudgetSummaryResponse> getBudgetsForMonth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {

        UUID userId = getCurrentUserId();
        LocalDate targetMonth = month != null ? month : LocalDate.now();
        BudgetSummaryResponse summary = budgetService.getBudgetsForMonth(userId, targetMonth);
        return ResponseEntity.ok(summary);
    }

    /**
     * Get budget progress vs actual spending
     * GET /api/budgets/progress?month=2024-12-01
     */
    @GetMapping("/progress")
    public ResponseEntity<Map<String, Object>> getBudgetProgress(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {

        UUID userId = getCurrentUserId();
        LocalDate targetMonth = month != null ? month : LocalDate.now();
        Map<String, Object> progress = budgetService.getBudgetProgress(userId, targetMonth);
        return ResponseEntity.ok(progress);
    }

    /**
     * Delete a budget
     * DELETE /api/budgets/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(@PathVariable UUID id) {
        try {
            UUID userId = getCurrentUserId();
            budgetService.deleteBudget(id, userId);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    /**
     * Get budget recommendations based on historical spending
     * GET /api/budgets/recommendations
     */
    @GetMapping("/recommendations")
    public ResponseEntity<Map<String, Double>> getBudgetRecommendations() {
        UUID userId = getCurrentUserId();
        Map<String, Double> recommendations = budgetService.getBudgetRecommendations(userId);
        return ResponseEntity.ok(recommendations);
    }

    /**
     * Get budgets that are exceeded
     * GET /api/budgets/exceeded
     */
    @GetMapping("/exceeded")
    public ResponseEntity<List<BudgetResponse>> getExceededBudgets() {
        UUID userId = getCurrentUserId();
        List<BudgetResponse> exceededBudgets = budgetService.getExceededBudgets(userId);
        return ResponseEntity.ok(exceededBudgets);
    }

    /**
     * Get budgets that need alerts (reached alert threshold)
     * GET /api/budgets/alerts
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<BudgetResponse>> getBudgetsNeedingAlert() {
        UUID userId = getCurrentUserId();
        List<BudgetResponse> alertBudgets = budgetService.getBudgetsNeedingAlert(userId);
        return ResponseEntity.ok(alertBudgets);
    }

    /**
     * Refresh budget spending for current month
     * POST /api/budgets/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshBudgetSpending(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {

        UUID userId = getCurrentUserId();
        LocalDate targetMonth = month != null ? month : LocalDate.now();
        budgetService.refreshAllBudgetSpending(userId, targetMonth);
        return ResponseEntity.ok(Map.of("message", "Budget spending refreshed successfully"));
    }

    /**
     * Copy previous month's budgets to current month
     * POST /api/budgets/copy-previous
     */
    @PostMapping("/copy-previous")
    public ResponseEntity<?> copyPreviousMonthBudgets() {
        try {
            UUID userId = getCurrentUserId();
            List<BudgetResponse> copiedBudgets = budgetService.copyPreviousMonthBudgets(userId);
            return ResponseEntity.ok(Map.of(
                    "message", "Budgets copied successfully",
                    "count", copiedBudgets.size(),
                    "budgets", copiedBudgets
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Helper method to get current authenticated user ID
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }
}
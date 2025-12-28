// src/main/java/com/financecoach/backend/controller/AnalyticsController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = "${app.frontend-url}")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Autowired
    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Get spending by category
     * ✅ UPDATED: Returns BigDecimal map
     */
    @GetMapping("/spending-by-category")
    public ResponseEntity<Map<String, BigDecimal>> getSpendingByCategory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        Map<String, BigDecimal> spending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        return ResponseEntity.ok(spending);
    }

    /**
     * Get total spending
     * ✅ UPDATED: Returns BigDecimal
     */
    @GetMapping("/total-spending")
    public ResponseEntity<Map<String, BigDecimal>> getTotalSpending(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        BigDecimal total = analyticsService.getTotalSpending(userId, startDate, endDate);
        return ResponseEntity.ok(Map.of("totalSpending", total));
    }

    /**
     * Get total income
     */
    @GetMapping("/total-income")
    public ResponseEntity<Map<String, BigDecimal>> getTotalIncome(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        BigDecimal total = analyticsService.getTotalIncome(userId, startDate, endDate);
        return ResponseEntity.ok(Map.of("totalIncome", total));
    }

    /**
     * Get top merchants
     */
    @GetMapping("/top-merchants")
    public ResponseEntity<Map<String, BigDecimal>> getTopMerchants(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "5") int limit) {

        UUID userId = getCurrentUserId();
        Map<String, BigDecimal> topMerchants = analyticsService.getTopMerchants(userId, startDate, endDate, limit);
        return ResponseEntity.ok(topMerchants);
    }

    /**
     * Get spending trend (day by day)
     */
    @GetMapping("/spending-trend")
    public ResponseEntity<Map<LocalDate, BigDecimal>> getSpendingTrend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        Map<LocalDate, BigDecimal> trend = analyticsService.getSpendingTrend(userId, startDate, endDate);
        return ResponseEntity.ok(trend);
    }

    /**
     * Get monthly summary
     * (BigDecimal for money, Integer for counts, String for dates, etc.)
     */
    @GetMapping("/monthly-summary")
    public ResponseEntity<Map<String, Object>> getMonthlySummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {

        UUID userId = getCurrentUserId();
        LocalDate targetMonth = month != null ? month : LocalDate.now();
        Map<String, Object> summary = analyticsService.getMonthlySummary(userId, targetMonth);
        return ResponseEntity.ok(summary);
    }

    /**
     * Compare current month vs previous month
     */
    @GetMapping("/compare-months")
    public ResponseEntity<Map<String, Object>> compareMonths() {
        UUID userId = getCurrentUserId();
        Map<String, Object> comparison = analyticsService.compareMonths(userId);
        return ResponseEntity.ok(comparison);
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }
}
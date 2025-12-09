// src/main/java/com/financecoach/userservice/controller/AnalyticsController.java
package com.financecoach.userservice.controller;

import com.financecoach.userservice.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Autowired
    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Get spending by category
     */
    @GetMapping("/spending-by-category")
    public ResponseEntity<Map<String, Double>> getSpendingByCategory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        Map<String, Double> spending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        return ResponseEntity.ok(spending);
    }

    /**
     * Get total spending
     */
    @GetMapping("/total-spending")
    public ResponseEntity<Map<String, Double>> getTotalSpending(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        Double total = analyticsService.getTotalSpending(userId, startDate, endDate);
        return ResponseEntity.ok(Map.of("totalSpending", total));
    }

    /**
     * Get top merchants
     */
    @GetMapping("/top-merchants")
    public ResponseEntity<Map<String, Double>> getTopMerchants(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "5") int limit) {

        UUID userId = getCurrentUserId();
        Map<String, Double> topMerchants = analyticsService.getTopMerchants(userId, startDate, endDate, limit);
        return ResponseEntity.ok(topMerchants);
    }

    /**
     * Get spending trend (day by day)
     */
    @GetMapping("/spending-trend")
    public ResponseEntity<Map<LocalDate, Double>> getSpendingTrend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        UUID userId = getCurrentUserId();
        Map<LocalDate, Double> trend = analyticsService.getSpendingTrend(userId, startDate, endDate);
        return ResponseEntity.ok(trend);
    }

    /**
     * Get monthly summary
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
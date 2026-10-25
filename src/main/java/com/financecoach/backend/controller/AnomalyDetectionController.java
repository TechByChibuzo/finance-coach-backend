package com.financecoach.backend.controller;

import com.financecoach.backend.model.SpendingAnomaly;
import com.financecoach.backend.security.CurrentUser;
import com.financecoach.backend.service.AnomalyDetectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/anomalies")
public class AnomalyDetectionController {

    private final AnomalyDetectionService anomalyDetectionService;

    @Autowired
    public AnomalyDetectionController(AnomalyDetectionService anomalyDetectionService) {
        this.anomalyDetectionService = anomalyDetectionService;
    }

    /**
     * Detect anomalies for current user
     * POST /api/anomalies/detect
     */
    @PostMapping("/detect")
    public ResponseEntity<List<SpendingAnomaly>> detectAnomalies() {
        UUID userId = CurrentUser.id();
        List<SpendingAnomaly> anomalies = anomalyDetectionService.detectAnomalies(userId);
        return ResponseEntity.ok(anomalies);
    }

    /**
     * Get all unreviewed anomalies
     * GET /api/anomalies/unreviewed
     */
    @GetMapping("/unreviewed")
    public ResponseEntity<List<SpendingAnomaly>> getUnreviewedAnomalies() {
        UUID userId = CurrentUser.id();
        List<SpendingAnomaly> anomalies = anomalyDetectionService.getUnreviewedAnomalies(userId);
        return ResponseEntity.ok(anomalies);
    }

    /**
     * Get all anomalies
     * GET /api/anomalies
     */
    @GetMapping
    public ResponseEntity<List<SpendingAnomaly>> getAllAnomalies() {
        UUID userId = CurrentUser.id();
        List<SpendingAnomaly> anomalies = anomalyDetectionService.getAllAnomalies(userId);
        return ResponseEntity.ok(anomalies);
    }

    /**
     * Get unreviewed anomaly count
     * GET /api/anomalies/count
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> getUnreviewedCount() {
        UUID userId = CurrentUser.id();
        long count = anomalyDetectionService.getUnreviewedCount(userId);
        return ResponseEntity.ok(Map.of("unreviewedCount", count));
    }

    /**
     * Mark anomaly as reviewed
     * PUT /api/anomalies/{anomalyId}/review
     */
    @PutMapping("/{anomalyId}/review")
    public ResponseEntity<Void> markAsReviewed(@PathVariable UUID anomalyId) {
        UUID userId = CurrentUser.id();
        anomalyDetectionService.markAsReviewed(anomalyId, userId);
        return ResponseEntity.noContent().build();
    }

}
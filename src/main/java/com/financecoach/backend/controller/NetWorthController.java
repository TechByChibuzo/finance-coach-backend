package com.financecoach.backend.controller;

import com.financecoach.backend.dto.networth.ManualAssetRequest;
import com.financecoach.backend.dto.networth.ManualAssetResponse;
import com.financecoach.backend.dto.networth.ManualLiabilityRequest;
import com.financecoach.backend.dto.networth.ManualLiabilityResponse;
import com.financecoach.backend.dto.networth.NetWorthSummaryDTO;
import com.financecoach.backend.dto.networth.NetWorthSnapshotDTO;
import com.financecoach.backend.service.NetWorthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/net-worth")
@CrossOrigin(origins = "${app.frontend-url}")
public class NetWorthController {

    private static final Logger logger = LoggerFactory.getLogger(NetWorthController.class);

    private final NetWorthService netWorthService;

    @Autowired
    public NetWorthController(NetWorthService netWorthService) {
        this.netWorthService = netWorthService;
    }

    /**
     * Get current net worth summary
     * GET /api/net-worth/current
     */
    @GetMapping("/current")
    public ResponseEntity<NetWorthSummaryDTO> getCurrentNetWorth() {
        UUID userId = getCurrentUserId();
        logger.info("Getting current net worth for user: {}", userId);

        NetWorthSummaryDTO summary = netWorthService.calculateNetWorth(userId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Get net worth history
     * GET /api/net-worth/history?days=30
     */
    @GetMapping("/history")
    public ResponseEntity<List<NetWorthSnapshotDTO>> getNetWorthHistory(
            @RequestParam(defaultValue = "30") int days) {

        UUID userId = getCurrentUserId();
        logger.info("Getting net worth history for user: {} (last {} days)", userId, days);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        List<NetWorthSnapshotDTO> history = netWorthService.getNetWorthHistory(userId, startDate, endDate);
        return ResponseEntity.ok(history);
    }

    // ==================== MANUAL ASSETS ====================

    /**
     * Get all manual assets for user
     * GET /api/net-worth/assets
     */
    @GetMapping("/assets")
    public ResponseEntity<List<ManualAssetResponse>> getAllAssets() {
        UUID userId = getCurrentUserId();
        logger.info("Getting all manual assets for user: {}", userId);

        List<ManualAssetResponse> assets = netWorthService.getAllAssets(userId);
        return ResponseEntity.ok(assets);
    }

    /**
     * Add a new manual asset
     * POST /api/net-worth/assets
     */
    @PostMapping("/assets")
    public ResponseEntity<ManualAssetResponse> addAsset(@Valid @RequestBody ManualAssetRequest request) {
        UUID userId = getCurrentUserId();
        logger.info("Adding manual asset for user: {} - {}", userId, request.getName());

        ManualAssetResponse asset = netWorthService.addAsset(userId, request);
        return ResponseEntity.ok(asset);
    }

    /**
     * Update a manual asset
     * PUT /api/net-worth/assets/{assetId}
     */
    @PutMapping("/assets/{assetId}")
    public ResponseEntity<ManualAssetResponse> updateAsset(
            @PathVariable UUID assetId,
            @Valid @RequestBody ManualAssetRequest request) {

        UUID userId = getCurrentUserId();
        logger.info("Updating manual asset: {} for user: {}", assetId, userId);

        ManualAssetResponse asset = netWorthService.updateAsset(userId, assetId, request);
        return ResponseEntity.ok(asset);
    }

    /**
     * Delete a manual asset
     * DELETE /api/net-worth/assets/{assetId}
     */
    @DeleteMapping("/assets/{assetId}")
    public ResponseEntity<Void> deleteAsset(@PathVariable UUID assetId) {
        UUID userId = getCurrentUserId();
        logger.info("Deleting manual asset: {} for user: {}", assetId, userId);

        netWorthService.deleteAsset(userId, assetId);
        return ResponseEntity.noContent().build();
    }

    // ==================== MANUAL LIABILITIES ====================

    /**
     * Get all manual liabilities for user
     * GET /api/net-worth/liabilities
     */
    @GetMapping("/liabilities")
    public ResponseEntity<List<ManualLiabilityResponse>> getAllLiabilities() {
        UUID userId = getCurrentUserId();
        logger.info("Getting all manual liabilities for user: {}", userId);

        List<ManualLiabilityResponse> liabilities = netWorthService.getAllLiabilities(userId);
        return ResponseEntity.ok(liabilities);
    }

    /**
     * Add a new manual liability
     * POST /api/net-worth/liabilities
     */
    @PostMapping("/liabilities")
    public ResponseEntity<ManualLiabilityResponse> addLiability(@Valid @RequestBody ManualLiabilityRequest request) {
        UUID userId = getCurrentUserId();
        logger.info("Adding manual liability for user: {} - {}", userId, request.getName());

        ManualLiabilityResponse liability = netWorthService.addLiability(userId, request);
        return ResponseEntity.ok(liability);
    }

    /**
     * Update a manual liability
     * PUT /api/net-worth/liabilities/{liabilityId}
     */
    @PutMapping("/liabilities/{liabilityId}")
    public ResponseEntity<ManualLiabilityResponse> updateLiability(
            @PathVariable UUID liabilityId,
            @Valid @RequestBody ManualLiabilityRequest request) {

        UUID userId = getCurrentUserId();
        logger.info("Updating manual liability: {} for user: {}", liabilityId, userId);

        ManualLiabilityResponse liability = netWorthService.updateLiability(userId, liabilityId, request);
        return ResponseEntity.ok(liability);
    }

    /**
     * Delete a manual liability
     * DELETE /api/net-worth/liabilities/{liabilityId}
     */
    @DeleteMapping("/liabilities/{liabilityId}")
    public ResponseEntity<Void> deleteLiability(@PathVariable UUID liabilityId) {
        UUID userId = getCurrentUserId();
        logger.info("Deleting manual liability: {} for user: {}", liabilityId, userId);

        netWorthService.deleteLiability(userId, liabilityId);
        return ResponseEntity.noContent().build();
    }

    // ==================== HELPER METHODS ====================

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }
}
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.investment.HoldingDTO;
import com.financecoach.backend.dto.investment.PortfolioSnapshotDTO;
import com.financecoach.backend.dto.investment.PortfolioSummaryDTO;
import com.financecoach.backend.model.Holding;
import com.financecoach.backend.model.PortfolioSnapshot;
import com.financecoach.backend.service.InvestmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/investments")
@CrossOrigin(origins = "${app.frontend-url}")
public class InvestmentController {

    private static final Logger logger = LoggerFactory.getLogger(InvestmentController.class);

    @Autowired
    private InvestmentService investmentService;

    /**
     * Sync holdings from Plaid for all investment accounts
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> syncHoldings(Authentication authentication) {
        UUID userId = getCurrentUserId();
        logger.info("Syncing holdings for user: {}", userId);

        try {
            investmentService.syncAllHoldings(userId);
            return ResponseEntity.ok(Map.of(
                    "message", "Holdings synced successfully",
                    "status", "success"
            ));
        } catch (Exception e) {
            logger.error("Failed to sync holdings for user: {}", userId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Failed to sync holdings: " + e.getMessage(),
                    "status", "error"
            ));
        }
    }

    /**
     * Sync holdings for a specific account
     */
    @PostMapping("/sync/{accountId}")
    public ResponseEntity<Map<String, String>> syncHoldingsForAccount(
            @PathVariable UUID accountId,
            Authentication authentication) {
        UUID userId = getCurrentUserId();
        logger.info("Syncing holdings for account: {}, user: {}", accountId, userId);

        try {
            investmentService.syncHoldingsForAccount(accountId, userId);
            return ResponseEntity.ok(Map.of(
                    "message", "Holdings synced successfully for account",
                    "status", "success"
            ));
        } catch (Exception e) {
            logger.error("Failed to sync holdings for account: {}", accountId, e);
            return ResponseEntity.badRequest().body(Map.of(
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    /**
     * Get portfolio summary
     */
    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioSummaryDTO> getPortfolio(Authentication authentication) {
        UUID userId = getCurrentUserId();
        logger.info("Getting portfolio summary for user: {}", userId);

        try {
            InvestmentService.PortfolioSummary summary = investmentService.getPortfolioSummary(userId);

            PortfolioSummaryDTO dto = PortfolioSummaryDTO.builder()
                    .totalValue(summary.getTotalValue())
                    .totalCostBasis(summary.getTotalCostBasis())
                    .totalGainLoss(summary.getTotalGainLoss())
                    .gainLossPercentage(summary.getGainLossPercentage())
                    .holdings(mapHoldingsToDTO(summary.getHoldings()))
                    .allocationBreakdown(summary.getAllocationBreakdown())
                    .build();

            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            logger.error("Failed to get portfolio for user: {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get holdings for a specific account
     */
    @GetMapping("/holdings/{accountId}")
    public ResponseEntity<List<HoldingDTO>> getHoldingsByAccount(
            @PathVariable UUID accountId,
            Authentication authentication) {
        UUID userId = getCurrentUserId();
        logger.info("Getting holdings for account: {}, user: {}", accountId, userId);

        try {
            List<Holding> holdings = investmentService.getHoldingsByAccount(accountId, userId);
            List<HoldingDTO> dtos = mapHoldingsToDTO(holdings);
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            logger.error("Failed to get holdings for account: {}", accountId, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get asset allocation breakdown
     */
    @GetMapping("/allocation")
    public ResponseEntity<Map<String, Object>> getAllocation(Authentication authentication) {
        UUID userId = getCurrentUserId();
        logger.info("Getting allocation breakdown for user: {}", userId);

        try {
            Map<String, java.math.BigDecimal> allocation = investmentService.getAllocationBreakdown(userId);
            return ResponseEntity.ok(Map.of(
                    "allocation", allocation,
                    "timestamp", java.time.LocalDateTime.now()
            ));
        } catch (Exception e) {
            logger.error("Failed to get allocation for user: {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get portfolio history
     */
    @GetMapping("/history")
    public ResponseEntity<List<PortfolioSnapshotDTO>> getPortfolioHistory(
            @RequestParam(defaultValue = "30") int days,
            Authentication authentication) {
        UUID userId = getCurrentUserId();
        logger.info("Getting portfolio history for user: {} - {} days", userId, days);

        try {
            List<PortfolioSnapshot> snapshots = investmentService.getPortfolioHistory(userId, days);
            List<PortfolioSnapshotDTO> dtos = snapshots.stream()
                    .map(snapshot -> PortfolioSnapshotDTO.builder()
                            .date(snapshot.getSnapshotDate())
                            .totalValue(snapshot.getTotalValue())
                            .totalGainLoss(snapshot.getTotalGainLoss())
                            .gainLossPercentage(snapshot.getGainLossPercentage())
                            .build())
                    .collect(Collectors.toList());

            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            logger.error("Failed to get portfolio history for user: {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Helper: Map holdings to DTOs
     */
    private List<HoldingDTO> mapHoldingsToDTO(List<Holding> holdings) {
        return holdings.stream()
                .map(holding -> HoldingDTO.builder()
                        .id(holding.getId())
                        .accountId(holding.getAccountId())
                        .accountName(holding.getAccount() != null ? holding.getAccount().getInstitutionName() : null)
                        .symbol(holding.getSymbol())
                        .name(holding.getName())
                        .type(holding.getType())
                        .quantity(holding.getQuantity())
                        .currentPrice(holding.getCurrentPrice())
                        .currentValue(holding.getCurrentValue())
                        .costBasis(holding.getCostBasis())
                        .gainLoss(holding.getGainLoss())
                        .gainLossPercentage(holding.getGainLossPercentage())
                        .currency(holding.getCurrency())
                        .lastUpdated(holding.getLastUpdated())
                        .build())
                .collect(Collectors.toList());
    }


    // Helper method to get current authenticated user ID
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }
}
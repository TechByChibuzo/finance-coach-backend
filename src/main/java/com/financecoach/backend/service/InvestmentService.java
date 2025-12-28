package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.Holding;
import com.financecoach.backend.model.PortfolioSnapshot;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.HoldingRepository;
import com.financecoach.backend.repository.PortfolioSnapshotRepository;
import com.plaid.client.model.InvestmentsHoldingsGetResponse;
import com.plaid.client.model.Security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InvestmentService {

    private static final Logger logger = LoggerFactory.getLogger(InvestmentService.class);

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private PortfolioSnapshotRepository snapshotRepository;

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private PlaidService plaidService;

    /**
     * Sync holdings from Plaid for a specific account
     */
    @Transactional
    public void syncHoldingsForAccount(UUID accountId, UUID userId) {
        logger.info("Syncing holdings for account: {}, user: {}", accountId, userId);

        // Verify account belongs to user
        BankAccount account = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new BankAccountNotFoundException(accountId));

        if (!account.getUserId().equals(userId)) {
            logger.warn("Unauthorized access attempt - User: {} tried to access account: {}",
                    userId, accountId);
            throw new UnauthorizedAccessException("You don't have access to this account");
        }

        // Only sync investment accounts
        if (!"investment".equalsIgnoreCase(account.getAccountType())) {
            logger.warn("Account {} is not an investment account, skipping sync", accountId);
            return;
        }

        try {
            // Fetch holdings from Plaid
            InvestmentsHoldingsGetResponse response = plaidService.getHoldings(account.getPlaidAccessToken());
            int processedCount = 0;

            // Get securities list
            List<Security> securities = response.getSecurities();

            // Process each holding
            for (com.plaid.client.model.Holding plaidHolding : response.getHoldings()) {
                Holding holding = saveOrUpdateHolding(account, plaidHolding, securities);
                processedCount ++;

                logger.debug("Processed holding: {} ({}) - Quantity: {}, Value: {}",
                        holding.getName(),
                        holding.getSymbol(),
                        holding.getQuantity(),
                        holding.getCurrentValue());
            }

            // Update last synced timestamp
            account.setLastSyncedAt(LocalDateTime.now());
            bankAccountRepository.save(account);

            logger.info("Holdings sync completed - Account: {}, Holdings processed: {}",
                    accountId, processedCount);

        } catch (PlaidIntegrationException e) {
            logger.error("Failed to sync holdings from Plaid for account: {}", accountId, e);
            throw e;
        }
    }

    /**
     * Sync holdings for all investment accounts of a user
     */
    @Transactional
    public void syncAllHoldings(UUID userId) {
        logger.info("Syncing all investment holdings for user: {}", userId);

        List<BankAccount> investmentAccounts = bankAccountRepository
                .findByUserIdAndAccountTypeAndIsActive(userId, "investment", true);

        if (investmentAccounts.isEmpty()) {
            logger.info("No active investment accounts found for user: {}", userId);
            return;
        }

        int successCount = 0;
        int failureCount = 0;

        for (BankAccount account : investmentAccounts) {
            try {
                syncHoldingsForAccount(account.getId(), userId);
                successCount++;
            } catch (Exception e) {
                logger.error("Failed to sync holdings for account: {}", account.getId(), e);
                failureCount++;
            }
        }

        logger.info("Holdings sync summary - User: {}, Success: {}, Failed: {}",
                userId, successCount, failureCount);
    }

    /**
     * Save or update a holding from Plaid data (UPDATED for 38.x)
     */
    private Holding saveOrUpdateHolding(
            BankAccount account,
            com.plaid.client.model.Holding plaidHolding,
            List<Security> securities) {

        Optional<Holding> existingHolding = holdingRepository
                .findByAccountIdAndSecurityId(account.getId(), plaidHolding.getSecurityId());

        Holding holding = existingHolding.orElse(new Holding());

        // Map Plaid data to our entity
        holding.setUserId(account.getUserId());
        holding.setAccountId(account.getId());
        holding.setSecurityId(plaidHolding.getSecurityId());

        // Find matching security
        Security security = securities.stream()
                .filter(s -> s.getSecurityId().equals(plaidHolding.getSecurityId()))
                .findFirst()
                .orElse(null);

        if (security != null) {
            holding.setSymbol(security.getTickerSymbol());
            holding.setName(security.getName());
            holding.setType(mapSecurityType(security.getType()));
        }

        // Quantities and values
        holding.setQuantity(BigDecimal.valueOf(plaidHolding.getQuantity()));
        holding.setCurrentPrice(BigDecimal.valueOf(plaidHolding.getInstitutionPrice()));
        holding.setCurrentValue(BigDecimal.valueOf(plaidHolding.getInstitutionValue()));
        holding.setInstitutionPrice(BigDecimal.valueOf(plaidHolding.getInstitutionPrice()));
        holding.setInstitutionValue(BigDecimal.valueOf(plaidHolding.getInstitutionValue()));

        // Cost basis (if available)
        if (plaidHolding.getCostBasis() != null) {
            holding.setCostBasis(BigDecimal.valueOf(plaidHolding.getCostBasis()));
        }

        holding.setCurrency(plaidHolding.getIsoCurrencyCode());
        holding.setLastUpdated(LocalDateTime.now());

        return holdingRepository.save(holding);
    }

    /**
     * Map Plaid security type to our simplified type
     */
    private String mapSecurityType(String plaidType) {
        if (plaidType == null) return "other";

        return switch (plaidType.toLowerCase()) {
            case "equity", "stock" -> "stock";
            case "etf" -> "etf";
            case "mutual fund" -> "mutual_fund";
            case "bond", "fixed income" -> "bond";
            case "cash", "cash equivalent" -> "cash";
            case "cryptocurrency" -> "crypto";
            default -> "other";
        };
    }

    /**
     * Get portfolio summary for a user
     */
    public PortfolioSummary getPortfolioSummary(UUID userId) {
        logger.debug("Getting portfolio summary for user: {}", userId);

        List<Holding> holdings = holdingRepository.findByUserId(userId);

        if (holdings.isEmpty()) {
            logger.info("No holdings found for user: {}", userId);
            return PortfolioSummary.builder()
                    .totalValue(BigDecimal.ZERO)
                    .totalCostBasis(BigDecimal.ZERO)
                    .totalGainLoss(BigDecimal.ZERO)
                    .gainLossPercentage(BigDecimal.ZERO)
                    .holdings(Collections.emptyList())
                    .allocationBreakdown(Collections.emptyMap())
                    .build();
        }

        // Calculate totals
        BigDecimal totalValue = holdings.stream()
                .map(Holding::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCostBasis = holdings.stream()
                .map(h -> h.getCostBasis() != null ? h.getCostBasis() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalGainLoss = totalValue.subtract(totalCostBasis);

        BigDecimal gainLossPercentage = totalCostBasis.compareTo(BigDecimal.ZERO) > 0
                ? totalGainLoss.divide(totalCostBasis, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        // Calculate allocation breakdown
        Map<String, BigDecimal> allocationBreakdown = holdings.stream()
                .collect(Collectors.groupingBy(
                        Holding::getType,
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                Holding::getCurrentValue,
                                BigDecimal::add
                        )
                ));

        logger.info("Portfolio summary - User: {}, Total Value: {}, Gain/Loss: {}",
                userId, totalValue, totalGainLoss);

        return PortfolioSummary.builder()
                .totalValue(totalValue)
                .totalCostBasis(totalCostBasis)
                .totalGainLoss(totalGainLoss)
                .gainLossPercentage(gainLossPercentage)
                .holdings(holdings)
                .allocationBreakdown(allocationBreakdown)
                .build();
    }

    /**
     * Get allocation breakdown (stocks, bonds, cash, etc.)
     */
    public Map<String, BigDecimal> getAllocationBreakdown(UUID userId) {
        logger.debug("Getting allocation breakdown for user: {}", userId);

        List<Object[]> results = holdingRepository.getAssetAllocationByUserId(userId);

        Map<String, BigDecimal> allocation = new HashMap<>();
        for (Object[] result : results) {
            String type = (String) result[0];
            BigDecimal value = (BigDecimal) result[1];
            allocation.put(type, value);
        }

        return allocation;
    }

    /**
     * Get holdings by account
     */
    public List<Holding> getHoldingsByAccount(UUID accountId, UUID userId) {
        logger.debug("Getting holdings for account: {}, user: {}", accountId, userId);

        // Verify access
        BankAccount account = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new BankAccountNotFoundException(accountId));

        if (!account.getUserId().equals(userId)) {
            throw new UnauthorizedAccessException("You don't have access to this account");
        }

        return holdingRepository.findByAccountId(accountId);
    }

    /**
     * Take daily portfolio snapshot (scheduled job)
     */
    @Scheduled(cron = "0 0 2 * * *")  // 2 AM daily
    @Transactional
    public void takePortfolioSnapshots() {
        logger.info("Taking daily portfolio snapshots");

        // Get all users with investment accounts
        List<UUID> userIds = bankAccountRepository.findDistinctUserIdsWithInvestmentAccounts();

        int successCount = 0;
        int failureCount = 0;

        for (UUID userId : userIds) {
            try {
                PortfolioSummary summary = getPortfolioSummary(userId);

                // Only save if user has holdings
                if (summary.getTotalValue().compareTo(BigDecimal.ZERO) > 0) {
                    PortfolioSnapshot snapshot = PortfolioSnapshot.builder()
                            .userId(userId)
                            .totalValue(summary.getTotalValue())
                            .totalCostBasis(summary.getTotalCostBasis())
                            .totalGainLoss(summary.getTotalGainLoss())
                            .gainLossPercentage(summary.getGainLossPercentage())
                            .snapshotDate(LocalDate.now())
                            .build();

                    snapshotRepository.save(snapshot);
                    successCount++;

                    logger.debug("Saved portfolio snapshot for user: {} - Value: {}",
                            userId, summary.getTotalValue());
                }
            } catch (Exception e) {
                logger.error("Failed to save portfolio snapshot for user: {}", userId, e);
                failureCount++;
            }
        }

        logger.info("Portfolio snapshots completed - Success: {}, Failed: {}",
                successCount, failureCount);
    }

    /**
     * Get portfolio history
     */
    public List<PortfolioSnapshot> getPortfolioHistory(UUID userId, int days) {
        logger.debug("Getting portfolio history for user: {} - {} days", userId, days);

        LocalDate startDate = LocalDate.now().minusDays(days);
        return snapshotRepository.findByUserIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
                userId, startDate);
    }

    /**
     * Auto-sync holdings during market hours
     * Runs every 30 minutes: 9:00 AM, 9:30 AM, 10:00 AM, ... 4:00 PM EST
     */
    @Scheduled(cron = "0 */30 9-16 ? * MON-FRI", zone = "America/New_York")
    @Transactional
    public void autoSyncHoldings() {
        logger.info("Starting automatic holdings sync");

        List<UUID> userIds = bankAccountRepository.findDistinctUserIdsWithInvestmentAccounts();

        if (userIds.isEmpty()) {
            logger.info("No users with investment accounts found");
            return;
        }

        logger.info("Syncing holdings for {} users", userIds.size());

        int successCount = 0;
        int failureCount = 0;

        for (UUID userId : userIds) {
            try {
                syncAllHoldings(userId);
                successCount++;

                // Rate limit: 1 user per 2 seconds (30 users/min, safe for Plaid)
                Thread.sleep(2000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Sync interrupted for user: {}", userId);
                break;
            } catch (Exception e) {
                logger.error("Failed to sync holdings for user: {}", userId, e);
                failureCount++;
            }
        }

        logger.info("Automatic sync completed - Success: {}, Failed: {}",
                successCount, failureCount);
    }

    /**
     * Inner class for portfolio summary
     */
    @lombok.Data
    @lombok.Builder
    public static class PortfolioSummary {
        private BigDecimal totalValue;
        private BigDecimal totalCostBasis;
        private BigDecimal totalGainLoss;
        private BigDecimal gainLossPercentage;
        private List<Holding> holdings;
        private Map<String, BigDecimal> allocationBreakdown;
    }
}
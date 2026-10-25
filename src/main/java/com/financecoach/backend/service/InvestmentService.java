package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.Holding;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.model.PortfolioSnapshot;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.HoldingRepository;
import com.financecoach.backend.repository.PortfolioSnapshotRepository;
import com.plaid.client.model.InvestmentsHoldingsGetResponse;
import com.plaid.client.model.Security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final HoldingRepository holdingRepository;

    private final PortfolioSnapshotRepository snapshotRepository;

    private final BankAccountRepository bankAccountRepository;

    private final PlaidService plaidService;

    public InvestmentService(HoldingRepository holdingRepository,
                             PortfolioSnapshotRepository snapshotRepository,
                             BankAccountRepository bankAccountRepository,
                             PlaidService plaidService) {
        this.holdingRepository = holdingRepository;
        this.snapshotRepository = snapshotRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.plaidService = plaidService;
    }

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

        syncHoldingsForItem(plaidService.getItem(account));
    }

    /**
     * Sync holdings for one Plaid item. The holdings response covers every account in the
     * item, so each holding is saved under the account Plaid reports for it.
     */
    private void syncHoldingsForItem(PlaidItem item) {
        Map<String, BankAccount> accountsByPlaidId = plaidService.getActiveAccountsByPlaidId(item);

        try {
            // Fetch holdings from Plaid
            InvestmentsHoldingsGetResponse response = plaidService.getHoldings(item.getAccessToken());
            int processedCount = 0;

            // Get securities list
            List<Security> securities = response.getSecurities();

            // Process each holding, remembering which positions each account still has
            Map<UUID, Set<String>> currentSecurities = new HashMap<>();
            for (com.plaid.client.model.Holding plaidHolding : response.getHoldings()) {
                BankAccount account = accountsByPlaidId.get(plaidHolding.getAccountId());
                if (account == null) {
                    continue; // account was disconnected or never linked
                }

                Holding holding = saveOrUpdateHolding(account, plaidHolding, securities);
                currentSecurities.computeIfAbsent(account.getId(), id -> new HashSet<>())
                        .add(plaidHolding.getSecurityId());
                processedCount ++;

                logger.debug("Processed holding: {} ({}) - Quantity: {}, Value: {}",
                        holding.getName(),
                        holding.getSymbol(),
                        holding.getQuantity(),
                        holding.getCurrentValue());
            }

            // The response is the item's complete current holdings: positions we still have stored
            // but Plaid no longer returns were sold, and must not count toward portfolio or net worth
            int removedCount = removeSoldHoldings(accountsByPlaidId.values(), currentSecurities);

            // The holdings response includes current balances for the item's investment accounts
            plaidService.applyBalances(item, response.getAccounts());

            // Update last synced timestamp
            for (BankAccount account : accountsByPlaidId.values()) {
                account.setLastSyncedAt(LocalDateTime.now());
                bankAccountRepository.save(account);
            }

            logger.info("Holdings sync completed - Item: {}, Holdings processed: {}, sold positions removed: {}",
                    item.getId(), processedCount, removedCount);

        } catch (PlaidIntegrationException e) {
            logger.error("Failed to sync holdings from Plaid for item: {}", item.getId(), e);
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

        for (PlaidItem item : plaidService.getItems(investmentAccounts)) {
            try {
                syncHoldingsForItem(item);
                successCount++;
            } catch (Exception e) {
                logger.error("Failed to sync holdings for item: {}", item.getId(), e);
                failureCount++;
            }
        }

        logger.info("Holdings sync summary - User: {}, Success: {}, Failed: {}",
                userId, successCount, failureCount);
    }

    private int removeSoldHoldings(Collection<BankAccount> accounts, Map<UUID, Set<String>> currentSecurities) {
        int removed = 0;
        for (BankAccount account : accounts) {
            Set<String> stillHeld = currentSecurities.getOrDefault(account.getId(), Set.of());
            List<Holding> sold = holdingRepository.findByAccountId(account.getId()).stream()
                    .filter(h -> !stillHeld.contains(h.getSecurityId()))
                    .toList();
            if (!sold.isEmpty()) {
                holdingRepository.deleteAll(sold);
                removed += sold.size();
            }
        }
        return removed;
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
        // name and type are required: Plaid sometimes omits a holding's security, so fill in
        // placeholders rather than fail the sync, but keep what an earlier sync stored
        if (holding.getName() == null) {
            holding.setName("Unknown security");
        }
        if (holding.getType() == null) {
            holding.setType(mapSecurityType(null));
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
     * Auto-sync holdings once a day, after the US market closes. Plaid refreshes holdings about
     * daily, so syncing more often was wasted work on the single scheduler thread. Runs before the
     * nightly portfolio snapshot.
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "America/New_York")
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
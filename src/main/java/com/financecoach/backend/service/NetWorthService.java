package com.financecoach.backend.service;

import com.financecoach.backend.dto.networth.*;
import com.financecoach.backend.model.*;
import com.financecoach.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NetWorthService {

    private static final Logger logger = LoggerFactory.getLogger(NetWorthService.class);

    @Autowired
    private BankAccountRepository bankAccountRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private ManualAssetRepository manualAssetRepository;

    @Autowired
    private ManualLiabilityRepository manualLiabilityRepository;

    @Autowired
    private NetWorthSnapshotRepository snapshotRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Calculate current net worth for a user
     * ✅ UPDATED: Returns DTO instead of inner class
     */
    public NetWorthSummaryDTO calculateNetWorth(UUID userId) {
        logger.info("Calculating net worth for user: {}", userId);

        // ASSETS
        BigDecimal cashBalance = getCashBalance(userId);
        BigDecimal investmentsValue = getInvestmentsValue(userId);
        BigDecimal manualAssetsValue = getManualAssetsValue(userId);
        BigDecimal totalAssets = cashBalance.add(investmentsValue).add(manualAssetsValue);

        // LIABILITIES
        BigDecimal creditCardDebt = getCreditCardDebt(userId);
        BigDecimal manualLiabilitiesValue = getManualLiabilitiesTotal(userId);
        BigDecimal totalLiabilities = creditCardDebt.add(manualLiabilitiesValue);

        // NET WORTH
        BigDecimal netWorth = totalAssets.subtract(totalLiabilities);

        logger.info("Net worth calculated - User: {}, Assets: {}, Liabilities: {}, Net Worth: {}",
                userId, totalAssets, totalLiabilities, netWorth);

        return NetWorthSummaryDTO.builder()
                .cashBalance(cashBalance)
                .investmentsValue(investmentsValue)
                .manualAssetsValue(manualAssetsValue)
                .totalAssets(totalAssets)
                .creditCardDebt(creditCardDebt)
                .manualLiabilities(manualLiabilitiesValue)
                .totalLiabilities(totalLiabilities)
                .netWorth(netWorth)
                .build();
    }

    /**
     * Get net worth history
     * ✅ UPDATED: Returns List<NetWorthSnapshotDTO>
     */
    public List<NetWorthSnapshotDTO> getNetWorthHistory(UUID userId, LocalDate startDate, LocalDate endDate) {
        logger.debug("Getting net worth history for user: {} from {} to {}", userId, startDate, endDate);

        List<NetWorthSnapshot> snapshots = snapshotRepository
                .findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(userId, startDate, endDate);

        return snapshots.stream()
                .map(this::mapToSnapshotDTO)
                .collect(Collectors.toList());
    }

    // ==================== MANUAL ASSETS ====================

    /**
     * Get all manual assets for user
     * ✅ UPDATED: Returns List<ManualAssetResponse>
     */
    public List<ManualAssetResponse> getAllAssets(UUID userId) {
        logger.debug("Getting manual assets for user: {}", userId);

        List<ManualAsset> assets = manualAssetRepository.findByUserId(userId);

        return assets.stream()
                .map(this::mapToAssetResponse)
                .collect(Collectors.toList());
    }

    /**
     * Add manual asset
     * ✅ UPDATED: Returns ManualAssetResponse
     */
    @Transactional
    public ManualAssetResponse addAsset(UUID userId, ManualAssetRequest request) {
        logger.info("Adding manual asset for user: {} - {}", userId, request.getName());

        ManualAsset asset = ManualAsset.builder()
                .userId(userId)
                .name(request.getName())
                .type(request.getType())
                .value(request.getValue())
                .notes(request.getNotes())
                .build();

        ManualAsset saved = manualAssetRepository.save(asset);
        return mapToAssetResponse(saved);
    }

    /**
     * Update manual asset
     * ✅ UPDATED: Returns ManualAssetResponse
     */
    @Transactional
    public ManualAssetResponse updateAsset(UUID userId, UUID assetId, ManualAssetRequest request) {
        logger.info("Updating manual asset: {} for user: {}", assetId, userId);

        ManualAsset asset = manualAssetRepository.findById(assetId)
                .orElseThrow(() -> new RuntimeException("Asset not found: " + assetId));

        if (!asset.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized access to asset");
        }

        asset.setName(request.getName());
        asset.setType(request.getType());
        asset.setValue(request.getValue());
        asset.setNotes(request.getNotes());

        ManualAsset updated = manualAssetRepository.save(asset);
        return mapToAssetResponse(updated);
    }

    /**
     * Delete manual asset
     */
    @Transactional
    public void deleteAsset(UUID userId, UUID assetId) {
        logger.info("Deleting manual asset: {} for user: {}", assetId, userId);

        ManualAsset asset = manualAssetRepository.findById(assetId)
                .orElseThrow(() -> new RuntimeException("Asset not found: " + assetId));

        if (!asset.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized access to asset");
        }

        manualAssetRepository.delete(asset);
    }

    // ==================== MANUAL LIABILITIES ====================

    /**
     * Get all manual liabilities for user
     * ✅ UPDATED: Returns List<ManualLiabilityResponse>
     */
    public List<ManualLiabilityResponse> getAllLiabilities(UUID userId) {
        logger.debug("Getting manual liabilities for user: {}", userId);

        List<ManualLiability> liabilities = manualLiabilityRepository.findByUserId(userId);

        return liabilities.stream()
                .map(this::mapToLiabilityResponse)
                .collect(Collectors.toList());
    }

    /**
     * Add manual liability
     * ✅ UPDATED: Returns ManualLiabilityResponse
     */
    @Transactional
    public ManualLiabilityResponse addLiability(UUID userId, ManualLiabilityRequest request) {
        logger.info("Adding manual liability for user: {} - {}", userId, request.getName());

        ManualLiability liability = ManualLiability.builder()
                .userId(userId)
                .name(request.getName())
                .type(request.getType())
                .balance(request.getBalance())
                .interestRate(request.getInterestRate())
                .monthlyPayment(request.getMonthlyPayment())
                .notes(request.getNotes())
                .build();

        ManualLiability saved = manualLiabilityRepository.save(liability);
        return mapToLiabilityResponse(saved);
    }

    /**
     * Update manual liability
     * ✅ UPDATED: Returns ManualLiabilityResponse
     */
    @Transactional
    public ManualLiabilityResponse updateLiability(UUID userId, UUID liabilityId, ManualLiabilityRequest request) {
        logger.info("Updating manual liability: {} for user: {}", liabilityId, userId);

        ManualLiability liability = manualLiabilityRepository.findById(liabilityId)
                .orElseThrow(() -> new RuntimeException("Liability not found: " + liabilityId));

        if (!liability.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized access to liability");
        }

        liability.setName(request.getName());
        liability.setType(request.getType());
        liability.setBalance(request.getBalance());
        liability.setInterestRate(request.getInterestRate());
        liability.setMonthlyPayment(request.getMonthlyPayment());
        liability.setNotes(request.getNotes());

        ManualLiability updated = manualLiabilityRepository.save(liability);
        return mapToLiabilityResponse(updated);
    }

    /**
     * Delete manual liability
     */
    @Transactional
    public void deleteLiability(UUID userId, UUID liabilityId) {
        logger.info("Deleting manual liability: {} for user: {}", liabilityId, userId);

        ManualLiability liability = manualLiabilityRepository.findById(liabilityId)
                .orElseThrow(() -> new RuntimeException("Liability not found: " + liabilityId));

        if (!liability.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized access to liability");
        }

        manualLiabilityRepository.delete(liability);
    }

    // ==================== SCHEDULED JOBS ====================

    /**
     * Take daily net worth snapshot (scheduled job)
     */
    @Scheduled(cron = "0 0 1 * * *")  // 1 AM daily
    @Transactional
    public void takeNetWorthSnapshots() {
        logger.info("Taking daily net worth snapshots for all users");

        List<User> users = userRepository.findAll();
        int successCount = 0;
        int failureCount = 0;

        for (User user : users) {
            try {
                NetWorthSummaryDTO summary = calculateNetWorth(user.getId());

                NetWorthSnapshot snapshot = NetWorthSnapshot.builder()
                        .userId(user.getId())
                        .cashBalance(summary.getCashBalance())
                        .investmentsValue(summary.getInvestmentsValue())
                        .manualAssetsValue(summary.getManualAssetsValue())
                        .totalAssets(summary.getTotalAssets())
                        .creditCardDebt(summary.getCreditCardDebt())
                        .manualLiabilities(summary.getManualLiabilities())
                        .totalLiabilities(summary.getTotalLiabilities())
                        .netWorth(summary.getNetWorth())
                        .snapshotDate(LocalDate.now())
                        .build();

                snapshotRepository.save(snapshot);
                successCount++;

                logger.debug("Saved net worth snapshot for user: {} - Net Worth: {}",
                        user.getId(), summary.getNetWorth());

            } catch (Exception e) {
                logger.error("Failed to save net worth snapshot for user: {}", user.getId(), e);
                failureCount++;
            }
        }

        logger.info("Net worth snapshots completed - Success: {}, Failed: {}",
                successCount, failureCount);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Get cash balance from depository accounts (checking, savings)
     */
    private BigDecimal getCashBalance(UUID userId) {
        List<BankAccount> cashAccounts = bankAccountRepository
                .findByUserIdAndAccountTypeInAndIsActive(
                        userId,
                        List.of("depository", "checking", "savings"),
                        true
                );

        return cashAccounts.stream()
                .map(BankAccount::getCurrentBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get total investments value from holdings
     */
    private BigDecimal getInvestmentsValue(UUID userId) {
        BigDecimal total = holdingRepository.getTotalPortfolioValueByUserId(userId);
        return total != null ? total : BigDecimal.ZERO;
    }

    /**
     * Get total value of manual assets (real estate, vehicles, etc.)
     */
    private BigDecimal getManualAssetsValue(UUID userId) {
        BigDecimal total = manualAssetRepository.getTotalValueByUserId(userId);
        return total != null ? total : BigDecimal.ZERO;
    }

    /**
     * Get credit card debt from credit accounts
     */
    private BigDecimal getCreditCardDebt(UUID userId) {
        List<BankAccount> creditAccounts = bankAccountRepository
                .findByUserIdAndAccountTypeAndIsActive(userId, "credit", true);

        // Credit card balances are positive (what you owe)
        return creditAccounts.stream()
                .map(BankAccount::getCurrentBalance)
                .map(BigDecimal::abs)  // Ensure positive
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get total manual liabilities (mortgages, loans, etc.)
     */
    private BigDecimal getManualLiabilitiesTotal(UUID userId) {
        BigDecimal total = manualLiabilityRepository.getTotalBalanceByUserId(userId);
        return total != null ? total : BigDecimal.ZERO;
    }

    // ==================== MAPPER METHODS ====================

    /**
     * Map ManualAsset entity to ManualAssetResponse DTO
     */
    private ManualAssetResponse mapToAssetResponse(ManualAsset asset) {
        return ManualAssetResponse.builder()
                .id(asset.getId())
                .name(asset.getName())
                .type(asset.getType())
                .value(asset.getValue())
                .notes(asset.getNotes())
                .createdAt(asset.getCreatedAt())
                .updatedAt(asset.getUpdatedAt())
                .build();
    }

    /**
     * Map ManualLiability entity to ManualLiabilityResponse DTO
     */
    private ManualLiabilityResponse mapToLiabilityResponse(ManualLiability liability) {
        return ManualLiabilityResponse.builder()
                .id(liability.getId())
                .name(liability.getName())
                .type(liability.getType())
                .balance(liability.getBalance())
                .interestRate(liability.getInterestRate())
                .monthlyPayment(liability.getMonthlyPayment())
                .notes(liability.getNotes())
                .createdAt(liability.getCreatedAt())
                .updatedAt(liability.getUpdatedAt())
                .build();
    }

    /**
     * Map NetWorthSnapshot entity to NetWorthSnapshotDTO
     */
    private NetWorthSnapshotDTO mapToSnapshotDTO(NetWorthSnapshot snapshot) {
        return NetWorthSnapshotDTO.builder()
                .date(snapshot.getSnapshotDate())
                .totalAssets(snapshot.getTotalAssets())
                .totalLiabilities(snapshot.getTotalLiabilities())
                .netWorth(snapshot.getNetWorth())
                .build();
    }
}
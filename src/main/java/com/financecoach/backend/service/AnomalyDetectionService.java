package com.financecoach.backend.service;

import com.financecoach.backend.model.SpendingAnomaly;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.SpendingAnomalyRepository;
import com.financecoach.backend.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AnomalyDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(AnomalyDetectionService.class);

    // Z-score threshold for anomaly detection
    // 2.0 = flagging anything 2 standard deviations above average
    private static final double Z_SCORE_THRESHOLD_LOW = 1.5;
    private static final double Z_SCORE_THRESHOLD_MEDIUM = 2.0;
    private static final double Z_SCORE_THRESHOLD_HIGH = 3.0;

    // Minimum months of history needed to detect anomalies
    private static final int MIN_MONTHS_HISTORY = 2;

    // Minimum spend to consider (ignore tiny amounts)
    private static final BigDecimal MIN_SPEND_THRESHOLD = new BigDecimal("5.00");

    private final TransactionRepository transactionRepository;
    private final SpendingAnomalyRepository anomalyRepository;

    public AnomalyDetectionService(TransactionRepository transactionRepository,
                                   SpendingAnomalyRepository anomalyRepository) {
        this.transactionRepository = transactionRepository;
        this.anomalyRepository = anomalyRepository;
    }

    /**
     * Detect anomalies for the current month compared to historical average
     */
    public List<SpendingAnomaly> detectAnomalies(UUID userId) {
        LocalDate today = LocalDate.now();
        LocalDate currentMonthStart = today.withDayOfMonth(1);
        LocalDate currentMonthEnd = today.plusDays(1);

        // Get 6 months of historical data for baseline
        LocalDate historyStart = currentMonthStart.minusMonths(6);
        LocalDate historyEnd = currentMonthStart.minusDays(1);

        logger.debug("Detecting anomalies for user {} from {} to {}",
                userId, historyStart, historyEnd);

        // Get all transactions
        List<Transaction> currentTransactions = transactionRepository
                .findByUserIdAndDateBetween(userId, currentMonthStart, currentMonthEnd);

        List<Transaction> historicalTransactions = transactionRepository
                .findByUserIdAndDateBetween(userId, historyStart, historyEnd);

        if (historicalTransactions.isEmpty()) {
            logger.debug("No historical data for user {}, skipping anomaly detection", userId);
            return List.of();
        }

        // Group current spending by category
        Map<String, BigDecimal> currentSpendByCategory = groupByCategory(currentTransactions);

        // Group historical spending by category and month
        Map<String, List<BigDecimal>> historicalSpendByCategory =
                groupByCategoryAndMonth(historicalTransactions);

        List<SpendingAnomaly> anomalies = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> entry : currentSpendByCategory.entrySet()) {
            String category = entry.getKey();
            BigDecimal currentSpend = entry.getValue();

            // Skip small amounts
            if (currentSpend.compareTo(MIN_SPEND_THRESHOLD) < 0) continue;

            List<BigDecimal> monthlySpends = historicalSpendByCategory.get(category);

            // Need enough history to detect anomalies
            if (monthlySpends == null || monthlySpends.size() < MIN_MONTHS_HISTORY) continue;

            // Calculate mean and standard deviation
            BigDecimal mean = calculateMean(monthlySpends);
            BigDecimal stdDev = calculateStdDev(monthlySpends, mean);

            // Skip if standard deviation is zero (spending never varies)
            if (stdDev.compareTo(BigDecimal.ZERO) == 0) continue;

            // Calculate z-score
            BigDecimal zScore = currentSpend.subtract(mean)
                    .divide(stdDev, 4, RoundingMode.HALF_UP);

            double zScoreDouble = zScore.doubleValue();

            // ADD THIS DEBUG LOG
            logger.info("Category: {} | Current: {} | Mean: {} | StdDev: {} | ZScore: {} | Months of history: {}",
                    category, currentSpend, mean, stdDev, zScore, monthlySpends.size());

            // Only flag if above threshold and it's higher than usual (not lower)
            if (zScoreDouble >= Z_SCORE_THRESHOLD_LOW) {
                String severity = determineSeverity(zScoreDouble);

                // Avoid duplicate anomalies for same period
                boolean exists = anomalyRepository
                        .existsByUserIdAndCategoryAndPeriodStartAndPeriodEnd(
                                userId, category, currentMonthStart, currentMonthEnd
                        );

                if (!exists) {
                    SpendingAnomaly anomaly = new SpendingAnomaly(
                            userId, category, currentSpend, mean,
                            stdDev, zScore, severity,
                            currentMonthStart, currentMonthEnd
                    );
                    anomalies.add(anomalyRepository.save(anomaly));

                    logger.info("Anomaly detected for user {} in category {}: " +
                                    "current=${} avg=${} z-score={} severity={}",
                            userId, category, currentSpend, mean, zScore, severity);
                }
            }
        }

        return anomalies;
    }

    public List<SpendingAnomaly> getUnreviewedAnomalies(UUID userId) {
        return anomalyRepository.findByUserIdAndIsReviewedFalseOrderByDetectedAtDesc(userId);
    }

    public List<SpendingAnomaly> getAllAnomalies(UUID userId) {
        return anomalyRepository.findByUserIdOrderByDetectedAtDesc(userId);
    }

    public long getUnreviewedCount(UUID userId) {
        return anomalyRepository.countByUserIdAndIsReviewedFalse(userId);
    }

    public void markAsReviewed(UUID anomalyId) {
        anomalyRepository.findById(anomalyId).ifPresent(anomaly -> {
            anomaly.markAsReviewed();
            anomalyRepository.save(anomaly);
        });
    }

    // ==================== HELPER METHODS ====================

    private Map<String, BigDecimal> groupByCategory(List<Transaction> transactions) {
        return transactions.stream()
                .filter(t -> t.getCategory() != null)
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) > 0) // only expenses
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));
    }

    private Map<String, List<BigDecimal>> groupByCategoryAndMonth(
            List<Transaction> transactions) {

        // Group by category, then sum per month
        Map<String, Map<String, BigDecimal>> byCategoryAndMonth = transactions.stream()
                .filter(t -> t.getCategory() != null)
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.groupingBy(
                                t -> t.getDate().getYear() + "-" + t.getDate().getMonthValue(),
                                Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                        )
                ));

        // Convert to category -> list of monthly totals
        return byCategoryAndMonth.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ArrayList<>(e.getValue().values())
                ));
    }

    private BigDecimal calculateMean(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStdDev(List<BigDecimal> values, BigDecimal mean) {
        BigDecimal sumSquaredDiffs = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = sumSquaredDiffs.divide(
                BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP
        );

        return variance.sqrt(new MathContext(4, RoundingMode.HALF_UP));
    }

    private String determineSeverity(double zScore) {
        if (zScore >= Z_SCORE_THRESHOLD_HIGH) return "HIGH";
        if (zScore >= Z_SCORE_THRESHOLD_MEDIUM) return "MEDIUM";
        return "LOW";
    }
}
package com.financecoach.backend.service;

import com.financecoach.backend.exception.AnomalyNotFoundException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.SpendingAnomaly;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.SpendingAnomalyRepository;
import com.financecoach.backend.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final Clock clock;

    @Autowired
    public AnomalyDetectionService(TransactionRepository transactionRepository,
                                   SpendingAnomalyRepository anomalyRepository) {
        this(transactionRepository, anomalyRepository, Clock.systemDefaultZone());
    }

    AnomalyDetectionService(TransactionRepository transactionRepository,
                            SpendingAnomalyRepository anomalyRepository, Clock clock) {
        this.transactionRepository = transactionRepository;
        this.anomalyRepository = anomalyRepository;
        this.clock = clock;
    }

    /**
     * Runs detection for every user with transactions, half an hour after the scheduled
     * transaction sync, so unusual spending shows up in notifications without anyone asking.
     * One user's failure doesn't stop the others.
     */
    @Scheduled(cron = "0 30 6,18 * * *")
    public void detectForAllUsers() {
        int checked = 0;
        int failed = 0;
        for (UUID userId : transactionRepository.findDistinctUserIds()) {
            try {
                detectAnomalies(userId);
                checked++;
            } catch (Exception e) {
                failed++;
                logger.error("Anomaly detection failed for user {}: {}", userId, e.getClass().getSimpleName());
            }
        }
        logger.info("Scheduled anomaly detection finished - users checked: {}, failed: {}", checked, failed);
    }

    /**
     * Detect anomalies for the current month compared to historical average.
     * There is at most one anomaly per user, category and month: running detection again
     * updates it with the latest figures instead of creating another one.
     */
    public List<SpendingAnomaly> detectAnomalies(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate currentMonthStart = today.withDayOfMonth(1);
        LocalDate currentMonthEnd = currentMonthStart.withDayOfMonth(currentMonthStart.lengthOfMonth());

        // Get 6 months of historical data for baseline
        LocalDate historyStart = currentMonthStart.minusMonths(6);
        LocalDate historyEnd = currentMonthStart.minusDays(1);

        logger.debug("Detecting anomalies for user {} from {} to {}",
                userId, historyStart, historyEnd);

        // Get all transactions
        List<Transaction> currentTransactions = transactionRepository
                .findByUserIdAndDateBetween(userId, currentMonthStart, today);

        List<Transaction> historicalTransactions = transactionRepository
                .findByUserIdAndDateBetween(userId, historyStart, historyEnd);

        if (historicalTransactions.isEmpty()) {
            logger.debug("No historical data for user {}, skipping anomaly detection", userId);
            return List.of();
        }

        // Group current spending by category
        Map<String, BigDecimal> currentSpendByCategory = groupByCategory(currentTransactions);

        // Each past month's spending up to the same day of the month as today, so a partial
        // month is compared with partial months
        Map<String, List<BigDecimal>> historicalSpendByCategory =
                monthToDateSpendByCategory(historicalTransactions, today.getDayOfMonth(),
                        YearMonth.from(historyEnd));

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

            // Spending figures stay at debug level: they are users' financial data
            logger.debug("Category: {} | Current: {} | Mean: {} | StdDev: {} | ZScore: {} | Months of history: {}",
                    category, currentSpend, mean, stdDev, zScore, monthlySpends.size());

            // Only flag if above threshold and it's higher than usual (not lower)
            if (zScoreDouble >= Z_SCORE_THRESHOLD_LOW) {
                String severity = determineSeverity(zScoreDouble);

                // One anomaly per category per month - update it if it already exists
                List<SpendingAnomaly> existing = anomalyRepository
                        .findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(
                                userId, category, currentMonthStart);

                if (!existing.isEmpty()) {
                    SpendingAnomaly anomaly = existing.get(0);
                    anomaly.refresh(currentSpend, mean, stdDev, zScore, severity, currentMonthEnd);
                    anomalies.add(anomalyRepository.save(anomaly));

                    // Clean up duplicates left by the old per-day period key
                    if (existing.size() > 1) {
                        anomalyRepository.deleteAll(existing.subList(1, existing.size()));
                        logger.info("Removed {} duplicate anomalies for user {} in category {}",
                                existing.size() - 1, userId, category);
                    }
                } else {
                    SpendingAnomaly anomaly = new SpendingAnomaly(
                            userId, category, currentSpend, mean,
                            stdDev, zScore, severity,
                            currentMonthStart, currentMonthEnd
                    );
                    anomalies.add(anomalyRepository.save(anomaly));

                    logger.info("Anomaly detected for user {} in category {} (severity {})",
                            userId, category, severity);
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

    public void markAsReviewed(UUID anomalyId, UUID userId) {
        SpendingAnomaly anomaly = anomalyRepository.findById(anomalyId)
                .orElseThrow(() -> new AnomalyNotFoundException(anomalyId));

        // Verify ownership
        if (!anomaly.getUserId().equals(userId)) {
            logger.warn("Unauthorized attempt to review anomaly: {} by user: {}", anomalyId, userId);
            throw new UnauthorizedAccessException("anomaly");
        }

        anomaly.markAsReviewed();
        anomalyRepository.save(anomaly);
    }

    // ==================== HELPER METHODS ====================

    private Map<String, BigDecimal> groupByCategory(List<Transaction> transactions) {
        return transactions.stream()
                .filter(t -> t.getCategory() != null)
                .filter(TransactionFilters::isSpending)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                ));
    }

    /**
     * For each category, the spend in each past month up to {@code dayOfMonth} (the whole month
     * when it's shorter). Months run from the user's first month of data to {@code lastMonth}, and a
     * month with no spending in a category counts as 0: leaving it out raised the average and
     * shrank the spread, which flagged normal spending. Months before the user's data starts
     * aren't counted at all.
     */
    private Map<String, List<BigDecimal>> monthToDateSpendByCategory(
            List<Transaction> transactions, int dayOfMonth, YearMonth lastMonth) {

        YearMonth firstMonth = transactions.stream()
                .map(t -> YearMonth.from(t.getDate()))
                .min(Comparator.naturalOrder())
                .orElse(lastMonth);
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = firstMonth; !m.isAfter(lastMonth); m = m.plusMonths(1)) {
            months.add(m);
        }

        Map<String, Map<YearMonth, BigDecimal>> byCategoryAndMonth = transactions.stream()
                .filter(t -> t.getCategory() != null)
                .filter(TransactionFilters::isSpending)
                .filter(t -> t.getDate().getDayOfMonth() <= dayOfMonth)
                .collect(Collectors.groupingBy(
                        Transaction::getCategory,
                        Collectors.groupingBy(
                                t -> YearMonth.from(t.getDate()),
                                Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)
                        )
                ));

        return byCategoryAndMonth.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> months.stream()
                                .map(m -> e.getValue().getOrDefault(m, BigDecimal.ZERO))
                                .toList()
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
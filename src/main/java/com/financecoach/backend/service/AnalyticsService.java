package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private final TransactionRepository transactionRepository;

    @Autowired
    public AnalyticsService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Get spending summary by category
     */
    public Map<String, BigDecimal> getSpendingByCategory(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) > 0) // Only expenses (positive amounts)
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "Uncategorized",
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                Transaction::getAmount,
                                BigDecimal::add
                        )
                ));
    }

    /**
     * Get total spending for a period
     */
    public BigDecimal getTotalSpending(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .map(Transaction::getAmount) // Only expenses
                .filter(amount -> amount.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get total income for a period
     */
    public BigDecimal getTotalIncome(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) < 0) // Only income (negative amounts in Plaid)
                .map(t -> t.getAmount().abs()) // Use BigDecimal.abs()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get top merchants by spending
     */
    public Map<String, BigDecimal> getTopMerchants(UUID userId, LocalDate startDate, LocalDate endDate, int limit) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(
                        t -> t.getMerchantName() != null ? t.getMerchantName() : t.getName(),
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                Transaction::getAmount,
                                BigDecimal::add
                        )
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    /**
     * Get spending trends (day by day)
     */
    public Map<LocalDate, BigDecimal> getSpendingTrend(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(
                        Transaction::getDate,
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                Transaction::getAmount,
                                BigDecimal::add
                        )
                ))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    /**
     * Get monthly summary
     */
    public Map<String, Object> getMonthlySummary(UUID userId, LocalDate month) {
        LocalDate startDate = month.withDayOfMonth(1);
        LocalDate endDate = month.withDayOfMonth(month.lengthOfMonth());

        BigDecimal totalSpending = getTotalSpending(userId, startDate, endDate);
        BigDecimal totalIncome = getTotalIncome(userId, startDate, endDate);
        Map<String, BigDecimal> categoryBreakdown = getSpendingByCategory(userId, startDate, endDate);
        Map<String, BigDecimal> topMerchants = getTopMerchants(userId, startDate, endDate, 5);

        BigDecimal netCashFlow = totalIncome.subtract(totalSpending);

        Map<String, Object> summary = new HashMap<>();
        summary.put("month", month.toString());
        summary.put("totalSpending", totalSpending);
        summary.put("totalIncome", totalIncome);
        summary.put("netCashFlow", netCashFlow);
        summary.put("categoryBreakdown", categoryBreakdown);
        summary.put("topMerchants", topMerchants);
        summary.put("transactionCount", transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate).size());

        return summary;
    }

    /**
     * Compare current month vs previous month
     */
    public Map<String, Object> compareMonths(UUID userId) {
        LocalDate now = LocalDate.now();
        LocalDate currentMonth = now.withDayOfMonth(1);
        LocalDate previousMonth = currentMonth.minusMonths(1);

        Map<String, Object> currentSummary = getMonthlySummary(userId, currentMonth);
        Map<String, Object> previousSummary = getMonthlySummary(userId, previousMonth);

        BigDecimal currentSpending = (BigDecimal) currentSummary.get("totalSpending");
        BigDecimal previousSpending = (BigDecimal) previousSummary.get("totalSpending");

        BigDecimal spendingChange = currentSpending.subtract(previousSpending);

        double percentageChange = 0.0;
        if (previousSpending.compareTo(BigDecimal.ZERO) > 0) {
            percentageChange = currentSpending
                    .subtract(previousSpending)
                    .divide(previousSpending, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .doubleValue();  // Convert to Double for display
        }

        Map<String, Object> comparison = new HashMap<>();
        comparison.put("currentMonth", currentSummary);
        comparison.put("previousMonth", previousSummary);
        comparison.put("spendingChange", spendingChange);  // BigDecimal (money difference)
        comparison.put("spendingChangePercentage", percentageChange);  // Double (display %)

        return comparison;
    }
}
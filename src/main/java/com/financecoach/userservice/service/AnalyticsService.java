// src/main/java/com/financecoach/userservice/service/AnalyticsService.java
package com.financecoach.userservice.service;

import com.financecoach.userservice.model.Transaction;
import com.financecoach.userservice.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    public Map<String, Double> getSpendingByCategory(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount() > 0) // Only expenses (positive amounts)
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "Uncategorized",
                        Collectors.summingDouble(Transaction::getAmount)
                ));
    }

    /**
     * Get total spending for a period
     */
    public Double getTotalSpending(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount() > 0) // Only expenses
                .mapToDouble(Transaction::getAmount)
                .sum();
    }

    /**
     * Get total income for a period
     */
    public Double getTotalIncome(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount() < 0) // Only income (negative amounts in Plaid)
                .mapToDouble(t -> Math.abs(t.getAmount()))
                .sum();
    }

    /**
     * Get top merchants by spending
     */
    public Map<String, Double> getTopMerchants(UUID userId, LocalDate startDate, LocalDate endDate, int limit) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount() > 0)
                .collect(Collectors.groupingBy(
                        t -> t.getMerchantName() != null ? t.getMerchantName() : t.getName(),
                        Collectors.summingDouble(Transaction::getAmount)
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
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
    public Map<LocalDate, Double> getSpendingTrend(UUID userId, LocalDate startDate, LocalDate endDate) {
        List<Transaction> transactions = transactionRepository
                .findByUserIdAndDateBetween(userId, startDate, endDate);

        return transactions.stream()
                .filter(t -> t.getAmount() > 0)
                .collect(Collectors.groupingBy(
                        Transaction::getDate,
                        Collectors.summingDouble(Transaction::getAmount)
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

        Double totalSpending = getTotalSpending(userId, startDate, endDate);
        Double totalIncome = getTotalIncome(userId, startDate, endDate);
        Map<String, Double> categoryBreakdown = getSpendingByCategory(userId, startDate, endDate);
        Map<String, Double> topMerchants = getTopMerchants(userId, startDate, endDate, 5);

        Map<String, Object> summary = new HashMap<>();
        summary.put("month", month.toString());
        summary.put("totalSpending", totalSpending);
        summary.put("totalIncome", totalIncome);
        summary.put("netCashFlow", totalIncome - totalSpending);
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

        Double currentSpending = (Double) currentSummary.get("totalSpending");
        Double previousSpending = (Double) previousSummary.get("totalSpending");

        Double percentageChange = 0.0;
        if (previousSpending > 0) {
            percentageChange = ((currentSpending - previousSpending) / previousSpending) * 100;
        }

        Map<String, Object> comparison = new HashMap<>();
        comparison.put("currentMonth", currentSummary);
        comparison.put("previousMonth", previousSummary);
        comparison.put("spendingChange", currentSpending - previousSpending);
        comparison.put("spendingChangePercentage", percentageChange);

        return comparison;
    }
}
// src/main/java/com/financecoach/userservice/service/BudgetService.java
package com.financecoach.backend.service;

import com.financecoach.backend.dto.BudgetRequest;
import com.financecoach.backend.dto.BudgetResponse;
import com.financecoach.backend.dto.BudgetSummaryResponse;
import com.financecoach.backend.model.Budget;
import com.financecoach.backend.repository.BudgetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final AnalyticsService analyticsService;
    @Autowired
    private MetricsService metricsService;

    @Autowired
    public BudgetService(BudgetRepository budgetRepository, AnalyticsService analyticsService) {
        this.budgetRepository = budgetRepository;
        this.analyticsService = analyticsService;
    }

    /**
     * Map Plaid transaction categories to user-friendly budget categories
     */
    private String mapPlaidCategoryToBudgetCategory(String plaidCategory) {
        if (plaidCategory == null) return "Other";

        return switch (plaidCategory.toUpperCase()) {
            case "FOOD_AND_DRINK" -> "Food & Dining";
            case "TRANSPORTATION" -> "Transportation";
            case "GENERAL_MERCHANDISE", "GENERAL_SERVICES", "SHOPS" -> "Shopping";
            case "ENTERTAINMENT", "RECREATION" -> "Entertainment";
            case "TRAVEL" -> "Travel";
            case "PERSONAL_CARE" -> "Personal Care";
            case "RENT_AND_UTILITIES" -> "Bills & Utilities";
            case "MEDICAL" -> "Healthcare";
            case "HOME_IMPROVEMENT" -> "Home";
            case "TRANSFER_IN", "TRANSFER_OUT" -> null; // Exclude transfers
            case "INCOME" -> null; // Exclude income
            default -> "Other";
        };
    }

    /**
     * Map raw Plaid categories to budget categories, combining similar categories
     */
    private Map<String, Double> mapCategoriesToBudgetCategories(Map<String, Double> rawSpending) {
        Map<String, Double> mappedSpending = new HashMap<>();

        for (Map.Entry<String, Double> entry : rawSpending.entrySet()) {
            String budgetCategory = mapPlaidCategoryToBudgetCategory(entry.getKey());

            // Skip null categories (transfers, income)
            if (budgetCategory != null) {
                mappedSpending.merge(budgetCategory, entry.getValue(), Double::sum);
            }
        }

        return mappedSpending;
    }

    /**
     * Create or update a budget for a category
     */
    @Transactional
    public BudgetResponse createOrUpdateBudget(UUID userId, BudgetRequest request) {
        // Validate inputs
        if (request.getCategory() == null || request.getCategory().isEmpty()) {
            throw new RuntimeException("Category is required");
        }
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new RuntimeException("Budget amount must be positive");
        }

        // Default to current month if not specified
        LocalDate month = request.getMonth();
        if (month == null) {
            month = LocalDate.now().withDayOfMonth(1);
        } else {
            // Normalize to first day of month
            month = month.withDayOfMonth(1);
        }

        // Check if budget already exists
        Budget budget = budgetRepository
                .findByUserIdAndCategoryAndMonth(userId, request.getCategory(), month)
                .orElse(null);

        if (budget != null) {
            // Update existing budget
            budget.setAmount(request.getAmount());
            budget.setIsActive(true);
            if (request.getNotes() != null) {
                budget.setNotes(request.getNotes());
            }
            if (request.getAlertThreshold() != null) {
                budget.setAlertThreshold(request.getAlertThreshold());
            }
        } else {
            // Create new budget
            budget = new Budget(userId, request.getCategory(), month, request.getAmount());
            budget.setIsActive(true);

            metricsService.recordBudgetCreated();
            if (request.getNotes() != null) {
                budget.setNotes(request.getNotes());
            }
            if (request.getAlertThreshold() != null) {
                budget.setAlertThreshold(request.getAlertThreshold());
            }
        }

        // Calculate current spending for this category
        LocalDate startDate = month;
        LocalDate endDate = month.plusMonths(1).minusDays(1);
        Map<String, Double> plaidSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, Double> categorySpending = mapCategoriesToBudgetCategories(plaidSpending);
        Double spent = categorySpending.getOrDefault(request.getCategory(), 0.0);
        budget.updateSpent(spent);

        // Save to database
        Budget savedBudget = budgetRepository.save(budget);
        budgetRepository.flush();

        return convertToResponse(savedBudget);
    }

    /**
     * Get all budgets for current month
     */
    public BudgetSummaryResponse getCurrentMonthBudgets(UUID userId) {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        return getBudgetsForMonth(userId, currentMonth);
    }

    /**
     * Get budgets for a specific month
     */
    public BudgetSummaryResponse getBudgetsForMonth(UUID userId, LocalDate month) {
        // Normalize to first day of month
        LocalDate normalizedMonth = month.withDayOfMonth(1);

        // Get all budgets for this month
        List<Budget> budgets = budgetRepository.findByUserIdAndMonthAndIsActive(
                userId, normalizedMonth, true);

        // Calculate actual spending for each category
        LocalDate startDate = normalizedMonth;
        LocalDate endDate = normalizedMonth.plusMonths(1).minusDays(1);
        Map<String, Double> rawSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, Double> actualSpending = mapCategoriesToBudgetCategories(rawSpending);

        // Update spent amounts
        for (Budget budget : budgets) {
            Double spent = actualSpending.getOrDefault(budget.getCategory(), 0.0);
            if (!spent.equals(budget.getSpent())) {
                budget.updateSpent(spent);
                budgetRepository.save(budget);
            }
        }

        // Convert to responses
        List<BudgetResponse> budgetResponses = budgets.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        // Calculate summary
        Double totalBudget = budgets.stream().mapToDouble(Budget::getAmount).sum();
        Double totalSpent = budgets.stream().mapToDouble(Budget::getSpent).sum();
        Double totalRemaining = totalBudget - totalSpent;
        Double percentageSpent = totalBudget > 0 ? (totalSpent / totalBudget) * 100 : 0.0;

        int exceededCount = (int) budgets.stream().filter(Budget::isExceeded).count();
        int alertCount = (int) budgets.stream().filter(Budget::shouldAlert).count();

        // Determine overall status
        String status;
        if (exceededCount > 0) {
            status = "exceeded";
        } else if (alertCount > 0 || percentageSpent >= 80) {
            status = "warning";
        } else {
            status = "on_track";
        }

        return new BudgetSummaryResponse(
                normalizedMonth,
                totalBudget,
                totalSpent,
                totalRemaining,
                percentageSpent,
                budgets.size(),
                exceededCount,
                alertCount,
                budgetResponses,
                status
        );
    }

    /**
     * Get budget progress vs actual spending
     */
    public Map<String, Object> getBudgetProgress(UUID userId, LocalDate month) {
        BudgetSummaryResponse summary = getBudgetsForMonth(userId, month);

        return Map.of(
                "month", summary.getMonth(),
                "totalBudget", summary.getTotalBudget(),
                "totalSpent", summary.getTotalSpent(),
                "remaining", summary.getTotalRemaining(),
                "percentageSpent", summary.getPercentageSpent(),
                "status", summary.getStatus(),
                "budgets", summary.getBudgets()
        );
    }

    /**
     * Delete a budget
     */
    @Transactional
    public void deleteBudget(UUID budgetId, UUID userId) {
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new RuntimeException("Budget not found"));

        // Verify ownership
        if (!budget.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized");
        }

        // Soft delete
        budget.setIsActive(false);
        budgetRepository.save(budget);
    }

    /**
     * Get budget recommendations based on historical spending
     */
    public Map<String, Double> getBudgetRecommendations(UUID userId) {
        // Get last 3 months of spending
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(3);

        Map<String, Double> rawSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, Double> categorySpending = mapCategoriesToBudgetCategories(rawSpending);


        // Calculate average monthly spending per category and add 10% buffer
        return categorySpending.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Math.ceil((entry.getValue() / 3) * 1.1) // Average + 10% buffer
                ));
    }

    /**
     * Get budgets that are exceeded
     */
    public List<BudgetResponse> getExceededBudgets(UUID userId) {
        List<Budget> exceededBudgets = budgetRepository.findExceededBudgets(userId);
        return exceededBudgets.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get budgets that need alerts
     */
    public List<BudgetResponse> getBudgetsNeedingAlert(UUID userId) {
        List<Budget> alertBudgets = budgetRepository.findBudgetsNeedingAlert(userId);
        return alertBudgets.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Refresh spent amounts for all budgets (useful for background jobs)
     */
    @Transactional
    public void refreshAllBudgetSpending(UUID userId, LocalDate month) {
        LocalDate normalizedMonth = month.withDayOfMonth(1);
        List<Budget> budgets = budgetRepository.findByUserIdAndMonthAndIsActive(
                userId, normalizedMonth, true);

        // Calculate actual spending
        LocalDate startDate = normalizedMonth;
        LocalDate endDate = normalizedMonth.plusMonths(1).minusDays(1);
        Map<String, Double> rawSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, Double> actualSpending = mapCategoriesToBudgetCategories(rawSpending);

        // Update each budget
        for (Budget budget : budgets) {
            Double spent = actualSpending.getOrDefault(budget.getCategory(), 0.0);
            budget.updateSpent(spent);
            budgetRepository.save(budget);
        }
    }

    /**
     * Copy budgets from previous month to current month
     */
    @Transactional
    public List<BudgetResponse> copyPreviousMonthBudgets(UUID userId) {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate previousMonth = currentMonth.minusMonths(1);

        // Get previous month's budgets
        List<Budget> previousBudgets = budgetRepository.findByUserIdAndMonthAndIsActive(
                userId, previousMonth, true);

        if (previousBudgets.isEmpty()) {
            throw new RuntimeException("No budgets found for previous month");
        }

        List<Budget> newBudgets = previousBudgets.stream()
                .map(prevBudget -> {
                    // Check if budget already exists for current month
                    boolean exists = budgetRepository.existsByUserIdAndCategoryAndMonth(
                            userId, prevBudget.getCategory(), currentMonth);

                    System.out.println("OLD BUDGET");
                    System.out.println(prevBudget.getId());
                    System.out.println(prevBudget.getAmount());
                    System.out.println(prevBudget.getCategory());
                    System.out.println(exists);

                    if (!exists) {
                        Budget newBudget = new Budget(
                                userId,
                                prevBudget.getCategory(),
                                currentMonth,
                                prevBudget.getAmount()
                        );
                        newBudget.setNotes(prevBudget.getNotes());
                        newBudget.setAlertThreshold(prevBudget.getAlertThreshold());
                        return budgetRepository.save(newBudget);
                    }
                    return null;
                })
                .filter(budget -> budget != null)
                .collect(Collectors.toList());

        // Refresh spending for new budgets
        refreshAllBudgetSpending(userId, currentMonth);

        return newBudgets.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    // Helper method to convert Entity to DTO
    private BudgetResponse convertToResponse(Budget budget) {
        return new BudgetResponse(
                budget.getId(),
                budget.getCategory(),
                budget.getMonth(),
                budget.getAmount(),
                budget.getSpent(),
                budget.getRemainingBudget(),
                budget.getPercentageSpent(),
                budget.getCurrencyCode(),
                budget.getNotes(),
                budget.getAlertThreshold(),
                budget.isExceeded(),
                budget.shouldAlert(),
                budget.getIsActive(),
                budget.getCreatedAt(),
                budget.getUpdatedAt()
        );
    }
}
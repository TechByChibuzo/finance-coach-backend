package com.financecoach.backend.service;

import com.financecoach.backend.dto.BudgetRequest;
import com.financecoach.backend.dto.BudgetResponse;
import com.financecoach.backend.dto.BudgetSummaryResponse;
import com.financecoach.backend.exception.BudgetNotFoundException;
import com.financecoach.backend.exception.FeatureNotAvailableException;
import com.financecoach.backend.exception.NoPreviousBudgetsException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.Budget;
import com.financecoach.backend.repository.BudgetRepository;
import com.financecoach.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BudgetService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetService.class);
    private static final String BUDGET_LIMIT_FEATURE = "budget_create";

    private final BudgetRepository budgetRepository;
    private final AnalyticsService analyticsService;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    public BudgetService(BudgetRepository budgetRepository, AnalyticsService analyticsService,
                         SubscriptionService subscriptionService, UserRepository userRepository) {
        this.budgetRepository = budgetRepository;
        this.analyticsService = analyticsService;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
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
    private Map<String, BigDecimal> mapCategoriesToBudgetCategories(Map<String, BigDecimal> rawSpending) {
        Map<String, BigDecimal> mappedSpending = new HashMap<>();

        for (Map.Entry<String, BigDecimal> entry : rawSpending.entrySet()) {
            String budgetCategory = mapPlaidCategoryToBudgetCategory(entry.getKey());

            // Skip null categories (transfers, income)
            if (budgetCategory != null) {
                mappedSpending.merge(
                        budgetCategory,
                        entry.getValue(),
                        BigDecimal::add  // Use BigDecimal.add instead of Double::sum
                );
            }
        }

        return mappedSpending;
    }

    /**
     * Create or update a budget for a category
     */
    @Transactional
    public BudgetResponse createOrUpdateBudget(UUID userId, BudgetRequest request) {
        logger.info("Creating/updating budget for user: {}, category: {}",
                userId, request.getCategory());

        // Validate inputs
        if (request.getCategory() == null || request.getCategory().isEmpty()) {
            logger.error("Budget creation failed - category is null or empty");
            throw new ValidationException("Category is required");
        }

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("Budget creation failed - invalid amount: {}", request.getAmount());
            throw new ValidationException("Budget amount must be positive");
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

        boolean isNewBudget = (budget == null);

        // A new budget, or re-activating a deleted one, adds to this month's count
        if (budget == null || !Boolean.TRUE.equals(budget.getIsActive())) {
            int remaining = remainingBudgetSlots(userId, month);
            if (remaining == 0) {
                throw new FeatureNotAvailableException(budgetLimitMessage(userId));
            }
        }

        if (budget != null) {
            logger.debug("Updating existing budget: {}", budget.getId());
            // Update existing budget
            budget.setAmount(request.getAmount());
            budget.setIsActive(true);
            if (request.getNotes() != null) {
                budget.setNotes(request.getNotes());
            }
            if (request.getAlertThreshold() != null) {
                budget.setAlertThreshold(request.getAlertThreshold());
            }
            if (request.getRollover() != null) {
                budget.setRollover(request.getRollover());
            }
        } else {
            logger.debug("Creating new budget for category: {}", request.getCategory());
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
            budget.setRollover(Boolean.TRUE.equals(request.getRollover()));
        }

        // Calculate current spending for this category
        LocalDate startDate = month;
        LocalDate endDate = month.plusMonths(1).minusDays(1);

        Map<String, BigDecimal> plaidSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, BigDecimal> categorySpending = mapCategoriesToBudgetCategories(plaidSpending);
        BigDecimal spent = categorySpending.getOrDefault(request.getCategory(), BigDecimal.ZERO);
        budget.updateSpent(spent);
        applyCarryOver(userId, month, List.of(budget));

        // Save to database
        Budget savedBudget = budgetRepository.save(budget);
        budgetRepository.flush();

        logger.info("Budget {} successfully - ID: {}, Category: {}, Amount: {}",
                isNewBudget ? "created" : "updated",
                savedBudget.getId(),
                savedBudget.getCategory(),
                savedBudget.getAmount());

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

        Map<String, BigDecimal> rawSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, BigDecimal> actualSpending = mapCategoriesToBudgetCategories(rawSpending);

        // Update spent amounts and what rolled over from last month
        Map<UUID, BigDecimal> carriedBefore = new HashMap<>();
        budgets.forEach(b -> carriedBefore.put(b.getId(), b.getCarriedOver()));
        applyCarryOver(userId, normalizedMonth, budgets);
        for (Budget budget : budgets) {
            BigDecimal spent = actualSpending.getOrDefault(budget.getCategory(), BigDecimal.ZERO);
            boolean carryChanged = !sameAmount(carriedBefore.get(budget.getId()), budget.getCarriedOver());

            if (spent.compareTo(budget.getSpent()) != 0 || carryChanged) {
                budget.updateSpent(spent);
                budgetRepository.save(budget);
            }
        }

        // Convert to responses
        List<BudgetResponse> budgetResponses = budgets.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        BigDecimal totalBudget = budgets.stream()
                .map(Budget::getAvailable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSpent = budgets.stream()
                .map(Budget::getSpent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRemaining = totalBudget.subtract(totalSpent);

        BigDecimal percentageSpentDecimal = totalBudget.compareTo(BigDecimal.ZERO) > 0
                ? totalSpent.divide(totalBudget, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        double percentageSpent = percentageSpentDecimal.doubleValue();
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
        logger.info("Deleting budget: {} for user: {}", budgetId, userId);

        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> {
                    logger.warn("Budget not found: {}", budgetId);
                    return new BudgetNotFoundException(budgetId);
                });

        // Verify ownership
        if (!budget.getUserId().equals(userId)) {
            logger.warn("Unauthorized attempt to delete budget: {} by user: {}",
                    budgetId, userId);
            throw new UnauthorizedAccessException("budget");
        }

        // Soft delete
        budget.setIsActive(false);
        budgetRepository.save(budget);
        logger.info("Budget soft-deleted successfully: {}", budgetId);
    }

    /**
     * Get budget recommendations based on historical spending
     */
    public Map<String, BigDecimal> getBudgetRecommendations(UUID userId) {
        // Get last 3 months of spending
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(3);

        Map<String, BigDecimal> rawSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, BigDecimal> categorySpending = mapCategoriesToBudgetCategories(rawSpending);

        // Calculate average monthly spending per category and add 10% buffer
        return categorySpending.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            // Average over 3 months
                            BigDecimal average = entry.getValue()
                                    .divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);

                            // Add 10% buffer
                            BigDecimal withBuffer = average.multiply(new BigDecimal("1.1"));

                            // Round up to nearest dollar
                            return withBuffer.setScale(0, RoundingMode.CEILING);
                        }
                ));
    }

    /**
     * Get budgets that are exceeded
     */
    public List<BudgetResponse> getExceededBudgets(UUID userId) {
        // This month's only, with fresh spending: last month's overspend isn't news now
        return getCurrentMonthBudgets(userId).getBudgets().stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsExceeded()))
                .collect(Collectors.toList());
    }

    /**
     * Get budgets that need alerts
     */
    public List<BudgetResponse> getBudgetsNeedingAlert(UUID userId) {
        return getCurrentMonthBudgets(userId).getBudgets().stream()
                .filter(b -> Boolean.TRUE.equals(b.getShouldAlert()))
                .collect(Collectors.toList());
    }

    /**
     * Refresh spent amounts for all budgets (useful for background jobs)
     * ✅ UPDATED: Now uses BigDecimal
     */
    @Transactional
    public void refreshAllBudgetSpending(UUID userId, LocalDate month) {
        LocalDate normalizedMonth = month.withDayOfMonth(1);
        List<Budget> budgets = budgetRepository.findByUserIdAndMonthAndIsActive(
                userId, normalizedMonth, true);

        // Calculate actual spending
        LocalDate startDate = normalizedMonth;
        LocalDate endDate = normalizedMonth.plusMonths(1).minusDays(1);
        Map<String, BigDecimal> rawSpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, BigDecimal> actualSpending = mapCategoriesToBudgetCategories(rawSpending);

        // Update each budget
        applyCarryOver(userId, normalizedMonth, budgets);
        for (Budget budget : budgets) {
            BigDecimal spent = actualSpending.getOrDefault(budget.getCategory(), BigDecimal.ZERO);
            budget.updateSpent(spent);
            budgetRepository.save(budget);
        }
    }

    /**
     * Copy budgets from previous month to current month
     */
    @Transactional
    public List<BudgetResponse> copyPreviousMonthBudgets(UUID userId) {
        logger.info("Copying previous month budgets for user: {}", userId);

        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate previousMonth = currentMonth.minusMonths(1);

        // Get previous month's budgets
        List<Budget> previousBudgets = budgetRepository.findByUserIdAndMonthAndIsActive(
                userId, previousMonth, true);

        if (previousBudgets.isEmpty()) {
            logger.warn("No previous budgets found for user: {}", userId);
            throw new NoPreviousBudgetsException();
        }

        logger.debug("Found {} budgets to copy from previous month", previousBudgets.size());

        // Copy only as many as the plan allows this month
        int remaining = remainingBudgetSlots(userId, currentMonth);
        if (remaining == 0) {
            throw new FeatureNotAvailableException(budgetLimitMessage(userId));
        }

        List<Budget> newBudgets = previousBudgets.stream()
                .map(prevBudget -> {
                    // Check if budget already exists for current month
                    boolean exists = budgetRepository.existsByUserIdAndCategoryAndMonth(
                            userId, prevBudget.getCategory(), currentMonth);

                    if (!exists) {
                        logger.debug("Copying budget - Category: {}, Amount: {}",
                                prevBudget.getCategory(), prevBudget.getAmount());

                        Budget newBudget = new Budget(
                                userId,
                                prevBudget.getCategory(),
                                currentMonth,
                                prevBudget.getAmount()
                        );
                        newBudget.setNotes(prevBudget.getNotes());
                        newBudget.setAlertThreshold(prevBudget.getAlertThreshold());
                        newBudget.setRollover(prevBudget.getRollover());
                        return budgetRepository.save(newBudget);
                    }
                    return null;
                })
                .filter(budget -> budget != null)
                .limit(remaining < 0 ? Long.MAX_VALUE : remaining)
                .collect(Collectors.toList());

        // Refresh spending for new budgets
        refreshAllBudgetSpending(userId, currentMonth);

        logger.info("Successfully copied {} budgets for user: {}", newBudgets.size(), userId);

        return newBudgets.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * How many more active budgets the user's plan allows for the month; -1 means unlimited.
     * Locks the user row until the caller's transaction ends, so two requests at the last slot
     * can't both count the same number of budgets and both take it.
     */
    private int remainingBudgetSlots(UUID userId, LocalDate month) {
        int limit = subscriptionService.getLimit(userId, BUDGET_LIMIT_FEATURE);
        if (limit < 0) {
            return -1;
        }
        userRepository.lockById(userId);
        int active = budgetRepository.findByUserIdAndMonthAndIsActive(userId, month, true).size();
        return Math.max(0, limit - active);
    }

    private String budgetLimitMessage(UUID userId) {
        return "Your plan allows " + subscriptionService.getLimit(userId, BUDGET_LIMIT_FEATURE)
                + " budgets per month. Upgrade to Premium for unlimited budgets.";
    }

    /**
     * For budgets with rollover, sets what's left of last month's budget in the same category
     * (negative when it was overspent). Last month's own carry-over counts too, so leftovers
     * build up month after month. Budgets without rollover carry nothing.
     */
    private void applyCarryOver(UUID userId, LocalDate month, List<Budget> budgets) {
        budgets.stream().filter(b -> !Boolean.TRUE.equals(b.getRollover())).forEach(b -> b.setCarriedOver(null));
        List<Budget> rolling = budgets.stream().filter(b -> Boolean.TRUE.equals(b.getRollover())).toList();
        if (rolling.isEmpty()) {
            return;
        }
        LocalDate previousMonth = month.minusMonths(1);
        Map<String, Budget> previous = budgetRepository.findByUserIdAndMonthAndIsActive(userId, previousMonth, true).stream()
                .collect(Collectors.toMap(Budget::getCategory, b -> b, (a, b) -> a));
        Map<String, BigDecimal> previousSpending = previous.isEmpty() ? Map.of() : mapCategoriesToBudgetCategories(
                analyticsService.getSpendingByCategory(userId, previousMonth, month.minusDays(1)));
        for (Budget budget : rolling) {
            Budget last = previous.get(budget.getCategory());
            budget.setCarriedOver(last == null ? null
                    : last.getAvailable().subtract(previousSpending.getOrDefault(budget.getCategory(), BigDecimal.ZERO)));
        }
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    // Helper method to convert Entity to DTO
    private BudgetResponse convertToResponse(Budget budget) {
        BudgetResponse response = new BudgetResponse(
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
        response.setRollover(Boolean.TRUE.equals(budget.getRollover()));
        response.setCarriedOver(budget.getCarriedOver());
        response.setAvailable(budget.getAvailable());
        return response;
    }
}
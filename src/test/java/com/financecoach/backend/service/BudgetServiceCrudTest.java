package com.financecoach.backend.service;

import com.financecoach.backend.dto.BudgetRequest;
import com.financecoach.backend.dto.BudgetResponse;
import com.financecoach.backend.dto.BudgetSummaryResponse;
import com.financecoach.backend.exception.BudgetNotFoundException;
import com.financecoach.backend.exception.NoPreviousBudgetsException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.Budget;
import com.financecoach.backend.repository.BudgetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Budget creation, monthly summaries, deletion, alerts and copying. Recommendation
 * maths is covered in BudgetServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class BudgetServiceCrudTest {

    @Mock private BudgetRepository budgetRepository;
    @Mock private AnalyticsService analyticsService;
    @Mock private MetricsService metricsService;
    @Mock private SubscriptionService subscriptionService;

    private BudgetService budgetService;
    private UUID userId;
    private LocalDate thisMonth;

    @BeforeEach
    void setUp() {
        budgetService = new BudgetService(budgetRepository, analyticsService, subscriptionService);
        // Unlimited budgets unless a test says otherwise
        org.mockito.Mockito.lenient().when(subscriptionService.getLimit(any(), eq("budget_create"))).thenReturn(-1);
        ReflectionTestUtils.setField(budgetService, "metricsService", metricsService);
        userId = UUID.randomUUID();
        thisMonth = LocalDate.now().withDayOfMonth(1);
    }

    // --- createOrUpdateBudget ---

    @Test
    void create_newBudget_normalizesMonth_calculatesSpent_andRecordsMetric() {
        BudgetRequest request = request("Food & Dining", "400", LocalDate.of(2026, 9, 17));
        request.setNotes("groceries + eating out");
        request.setAlertThreshold(90.0);
        when(budgetRepository.findByUserIdAndCategoryAndMonth(userId, "Food & Dining", LocalDate.of(2026, 9, 1)))
                .thenReturn(Optional.empty());
        when(analyticsService.getSpendingByCategory(userId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(Map.of("FOOD_AND_DRINK", new BigDecimal("150.25"), "TRANSFER_OUT", new BigDecimal("999")));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetResponse response = budgetService.createOrUpdateBudget(userId, request);

        assertThat(response.getMonth()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.getSpent()).isEqualByComparingTo("150.25");
        assertThat(response.getNotes()).isEqualTo("groceries + eating out");
        assertThat(response.getAlertThreshold()).isEqualTo(90.0);
        verify(metricsService).recordBudgetCreated();
    }

    @Test
    void create_withoutMonth_defaultsToCurrentMonth() {
        when(budgetRepository.findByUserIdAndCategoryAndMonth(userId, "Travel", thisMonth)).thenReturn(Optional.empty());
        when(analyticsService.getSpendingByCategory(eq(userId), eq(thisMonth), any())).thenReturn(Map.of());
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetResponse response = budgetService.createOrUpdateBudget(userId, request("Travel", "200", null));

        assertThat(response.getMonth()).isEqualTo(thisMonth);
        assertThat(response.getSpent()).isEqualByComparingTo("0");
    }

    @Test
    void update_existingBudget_changesAmount_andDoesNotCountAsCreated() {
        Budget existing = budget("Shopping", "100", "0");
        existing.setIsActive(false); // re-creating a deleted budget reactivates it
        when(budgetRepository.findByUserIdAndCategoryAndMonth(userId, "Shopping", thisMonth))
                .thenReturn(Optional.of(existing));
        when(analyticsService.getSpendingByCategory(eq(userId), eq(thisMonth), any()))
                .thenReturn(Map.of("GENERAL_MERCHANDISE", new BigDecimal("80"), "SHOPS", new BigDecimal("20")));
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetRequest request = request("Shopping", "250", thisMonth);
        request.setNotes("new note");
        request.setAlertThreshold(75.0);
        BudgetResponse response = budgetService.createOrUpdateBudget(userId, request);

        assertThat(response.getAmount()).isEqualByComparingTo("250");
        // Plaid categories that map to the same budget category are combined
        assertThat(response.getSpent()).isEqualByComparingTo("100");
        assertThat(response.getIsActive()).isTrue();
        assertThat(response.getNotes()).isEqualTo("new note");
        assertThat(response.getAlertThreshold()).isEqualTo(75.0);
        verify(metricsService, never()).recordBudgetCreated();
    }

    @Test
    void create_withMissingCategoryOrNonPositiveAmount_isRejected() {
        assertThatThrownBy(() -> budgetService.createOrUpdateBudget(userId, request("", "100", null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> budgetService.createOrUpdateBudget(userId, request("Food & Dining", "0", null)))
                .isInstanceOf(ValidationException.class);
        BudgetRequest noAmount = request("Food & Dining", "1", null);
        noAmount.setAmount(null);
        assertThatThrownBy(() -> budgetService.createOrUpdateBudget(userId, noAmount))
                .isInstanceOf(ValidationException.class);
        verify(budgetRepository, never()).save(any());
    }

    // --- plan limits ---

    @Test
    void create_atTheFreePlanLimit_isRefused() {
        when(subscriptionService.getLimit(userId, "budget_create")).thenReturn(3);
        when(budgetRepository.findByUserIdAndCategoryAndMonth(userId, "Travel", thisMonth)).thenReturn(Optional.empty());
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth, true)).thenReturn(List.of(
                budget("Food & Dining", "1", "0"), budget("Shopping", "1", "0"), budget("Entertainment", "1", "0")));

        assertThatThrownBy(() -> budgetService.createOrUpdateBudget(userId, request("Travel", "200", thisMonth)))
                .isInstanceOf(com.financecoach.backend.exception.FeatureNotAvailableException.class)
                .hasMessageContaining("3 budgets per month");
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void update_atTheLimit_isStillAllowed_butReactivatingADeletedOneCounts() {
        when(subscriptionService.getLimit(userId, "budget_create")).thenReturn(3);
        Budget active = budget("Food & Dining", "100", "0");
        Budget deleted = budget("Travel", "100", "0");
        deleted.setIsActive(false);
        when(budgetRepository.findByUserIdAndCategoryAndMonth(userId, "Food & Dining", thisMonth)).thenReturn(Optional.of(active));
        when(budgetRepository.findByUserIdAndCategoryAndMonth(userId, "Travel", thisMonth)).thenReturn(Optional.of(deleted));
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth, true)).thenReturn(List.of(
                active, budget("Shopping", "1", "0"), budget("Entertainment", "1", "0")));
        when(analyticsService.getSpendingByCategory(eq(userId), eq(thisMonth), any())).thenReturn(Map.of());
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(budgetService.createOrUpdateBudget(userId, request("Food & Dining", "150", thisMonth)).getAmount())
                .isEqualByComparingTo("150");
        assertThatThrownBy(() -> budgetService.createOrUpdateBudget(userId, request("Travel", "100", thisMonth)))
                .isInstanceOf(com.financecoach.backend.exception.FeatureNotAvailableException.class);
    }

    @Test
    void copyPreviousMonth_copiesOnlyUpToThePlanLimit() {
        when(subscriptionService.getLimit(userId, "budget_create")).thenReturn(3);
        LocalDate lastMonth = thisMonth.minusMonths(1);
        List<Budget> previous = java.util.stream.Stream.of("Food & Dining", "Travel", "Shopping", "Entertainment")
                .map(c -> { Budget b = budget(c, "100", "0"); b.setMonth(lastMonth); return b; }).toList();
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, lastMonth, true)).thenReturn(previous);
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth, true))
                .thenReturn(List.of(budget("Personal Care", "1", "0")))   // limit check: 1 already exists
                .thenReturn(List.of());                                    // refresh after copying
        when(budgetRepository.existsByUserIdAndCategoryAndMonth(eq(userId), any(), eq(thisMonth))).thenReturn(false);
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BudgetResponse> copied = budgetService.copyPreviousMonthBudgets(userId);

        assertThat(copied).extracting(BudgetResponse::getCategory).containsExactly("Food & Dining", "Travel");
    }

    @Test
    void copyPreviousMonth_withNoSlotsLeft_isRefused() {
        when(subscriptionService.getLimit(userId, "budget_create")).thenReturn(3);
        LocalDate lastMonth = thisMonth.minusMonths(1);
        Budget food = budget("Food & Dining", "100", "0");
        food.setMonth(lastMonth);
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, lastMonth, true)).thenReturn(List.of(food));
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth, true)).thenReturn(List.of(
                budget("Shopping", "1", "0"), budget("Travel", "1", "0"), budget("Entertainment", "1", "0")));

        assertThatThrownBy(() -> budgetService.copyPreviousMonthBudgets(userId))
                .isInstanceOf(com.financecoach.backend.exception.FeatureNotAvailableException.class);
    }

    // --- monthly summary ---

    @Test
    void getBudgetsForMonth_onTrack_whenUnderEveryLimit() {
        Budget food = budget("Food & Dining", "400", "0");
        stubMonth(List.of(food), Map.of("FOOD_AND_DRINK", new BigDecimal("100")));

        BudgetSummaryResponse summary = budgetService.getBudgetsForMonth(userId, thisMonth.plusDays(9));

        assertThat(summary.getMonth()).isEqualTo(thisMonth);
        assertThat(summary.getTotalBudget()).isEqualByComparingTo("400");
        assertThat(summary.getTotalSpent()).isEqualByComparingTo("100");
        assertThat(summary.getTotalRemaining()).isEqualByComparingTo("300");
        assertThat(summary.getPercentageSpent()).isEqualTo(25.0);
        assertThat(summary.getStatus()).isEqualTo("on_track");
        // spent changed from 0 to 100, so it is persisted
        verify(budgetRepository).save(food);
    }

    @Test
    void getBudgetsForMonth_warning_whenABudgetHitsItsAlertThreshold() {
        stubMonth(List.of(budget("Food & Dining", "100", "0"), budget("Travel", "1000", "0")),
                Map.of("FOOD_AND_DRINK", new BigDecimal("85")));

        BudgetSummaryResponse summary = budgetService.getBudgetsForMonth(userId, thisMonth);

        assertThat(summary.getAlertCount()).isEqualTo(1);
        assertThat(summary.getExceededCount()).isZero();
        assertThat(summary.getStatus()).isEqualTo("warning");
    }

    @Test
    void getBudgetsForMonth_exceeded_whenSpendingPassesALimit() {
        stubMonth(List.of(budget("Food & Dining", "100", "0")), Map.of("FOOD_AND_DRINK", new BigDecimal("130")));

        BudgetSummaryResponse summary = budgetService.getBudgetsForMonth(userId, thisMonth);

        assertThat(summary.getExceededCount()).isEqualTo(1);
        assertThat(summary.getStatus()).isEqualTo("exceeded");
        assertThat(summary.getBudgets().get(0).getIsExceeded()).isTrue();
    }

    @Test
    void getBudgetsForMonth_withNoBudgets_isZeroAndOnTrack_andUnchangedSpentIsNotSaved() {
        Budget unchanged = budget("Travel", "100", "0");
        stubMonth(List.of(unchanged), Map.of());

        BudgetSummaryResponse summary = budgetService.getBudgetsForMonth(userId, thisMonth);
        stubMonth(List.of(), Map.of());
        BudgetSummaryResponse empty = budgetService.getBudgetsForMonth(userId, thisMonth);

        verify(budgetRepository, never()).save(any());
        assertThat(summary.getStatus()).isEqualTo("on_track");
        assertThat(empty.getTotalBudget()).isEqualByComparingTo("0");
        assertThat(empty.getPercentageSpent()).isEqualTo(0.0);
        assertThat(empty.getCategoriesCount()).isZero();
    }

    @Test
    void getCurrentMonthBudgets_andProgress_useTheCurrentMonthSummary() {
        stubMonth(List.of(budget("Food & Dining", "200", "0")), Map.of("FOOD_AND_DRINK", new BigDecimal("50")));

        BudgetSummaryResponse current = budgetService.getCurrentMonthBudgets(userId);
        Map<String, Object> progress = budgetService.getBudgetProgress(userId, thisMonth);

        assertThat(current.getTotalSpent()).isEqualByComparingTo("50");
        assertThat(progress).containsEntry("status", "on_track");
        assertThat((BigDecimal) progress.get("remaining")).isEqualByComparingTo("150");
        assertThat(progress).containsKeys("month", "totalBudget", "totalSpent", "percentageSpent", "budgets");
    }

    // --- delete ---

    @Test
    void deleteBudget_byOwner_softDeletes() {
        Budget budget = budget("Food & Dining", "100", "0");
        when(budgetRepository.findById(budget.getId())).thenReturn(Optional.of(budget));

        budgetService.deleteBudget(budget.getId(), userId);

        assertThat(budget.getIsActive()).isFalse();
        verify(budgetRepository).save(budget);
    }

    @Test
    void deleteBudget_ofAnotherUser_isRejected_andUnknown_isNotFound() {
        Budget theirs = budget("Food & Dining", "100", "0");
        theirs.setUserId(UUID.randomUUID());
        UUID missing = UUID.randomUUID();
        when(budgetRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(budgetRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> budgetService.deleteBudget(theirs.getId(), userId))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThatThrownBy(() -> budgetService.deleteBudget(missing, userId))
                .isInstanceOf(BudgetNotFoundException.class);
        verify(budgetRepository, never()).save(any());
    }

    // --- alerts ---

    @Test
    void exceededAndAlertBudgets_comeFromTheRepository() {
        when(budgetRepository.findExceededBudgets(userId)).thenReturn(List.of(budget("Travel", "100", "150")));
        when(budgetRepository.findBudgetsNeedingAlert(userId)).thenReturn(List.of(budget("Food & Dining", "100", "90")));

        assertThat(budgetService.getExceededBudgets(userId)).extracting(BudgetResponse::getCategory)
                .containsExactly("Travel");
        assertThat(budgetService.getBudgetsNeedingAlert(userId)).extracting(BudgetResponse::getShouldAlert)
                .containsExactly(true);
    }

    // --- refresh + copy ---

    @Test
    void refreshAllBudgetSpending_updatesEveryBudget() {
        Budget food = budget("Food & Dining", "100", "0");
        Budget travel = budget("Travel", "100", "40");
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth, true)).thenReturn(List.of(food, travel));
        when(analyticsService.getSpendingByCategory(eq(userId), eq(thisMonth), any()))
                .thenReturn(Map.of("FOOD_AND_DRINK", new BigDecimal("60")));

        budgetService.refreshAllBudgetSpending(userId, thisMonth.plusDays(3));

        assertThat(food.getSpent()).isEqualByComparingTo("60");
        assertThat(travel.getSpent()).isEqualByComparingTo("0");
        verify(budgetRepository, times(2)).save(any());
    }

    @Test
    void copyPreviousMonthBudgets_copiesMissingCategories_andSkipsExistingOnes() {
        LocalDate lastMonth = thisMonth.minusMonths(1);
        Budget food = budget("Food & Dining", "300", "250");
        food.setMonth(lastMonth);
        food.setNotes("keep this");
        Budget travel = budget("Travel", "100", "0");
        travel.setMonth(lastMonth);
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, lastMonth, true)).thenReturn(List.of(food, travel));
        when(budgetRepository.existsByUserIdAndCategoryAndMonth(userId, "Food & Dining", thisMonth)).thenReturn(false);
        when(budgetRepository.existsByUserIdAndCategoryAndMonth(userId, "Travel", thisMonth)).thenReturn(true);
        when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth, true)).thenReturn(List.of());

        List<BudgetResponse> copied = budgetService.copyPreviousMonthBudgets(userId);

        assertThat(copied).hasSize(1);
        assertThat(copied.get(0).getCategory()).isEqualTo("Food & Dining");
        assertThat(copied.get(0).getMonth()).isEqualTo(thisMonth);
        assertThat(copied.get(0).getAmount()).isEqualByComparingTo("300");
        assertThat(copied.get(0).getNotes()).isEqualTo("keep this");
        // Spending starts fresh in the new month
        assertThat(copied.get(0).getSpent()).isEqualByComparingTo("0");
    }

    @Test
    void copyPreviousMonthBudgets_withNothingToCopy_throws() {
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth.minusMonths(1), true))
                .thenReturn(List.of());

        assertThatThrownBy(() -> budgetService.copyPreviousMonthBudgets(userId))
                .isInstanceOf(NoPreviousBudgetsException.class);
    }

    // --- helpers ---

    private void stubMonth(List<Budget> budgets, Map<String, BigDecimal> spending) {
        when(budgetRepository.findByUserIdAndMonthAndIsActive(userId, thisMonth, true)).thenReturn(budgets);
        when(analyticsService.getSpendingByCategory(userId, thisMonth, thisMonth.plusMonths(1).minusDays(1)))
                .thenReturn(spending);
    }

    private Budget budget(String category, String amount, String spent) {
        Budget budget = new Budget(userId, category, thisMonth, new BigDecimal(amount));
        budget.setId(UUID.randomUUID());
        budget.updateSpent(new BigDecimal(spent));
        return budget;
    }

    private BudgetRequest request(String category, String amount, LocalDate month) {
        BudgetRequest request = new BudgetRequest();
        request.setCategory(category);
        request.setAmount(new BigDecimal(amount));
        request.setMonth(month);
        return request;
    }
}

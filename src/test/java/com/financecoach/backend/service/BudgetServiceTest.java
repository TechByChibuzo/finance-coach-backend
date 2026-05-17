package com.financecoach.backend.service;

import com.financecoach.backend.repository.BudgetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private MetricsService metricsService;

    @InjectMocks
    private BudgetService budgetService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    // --- getBudgetRecommendations: category mapping ---

    @Test
    void getBudgetRecommendations_mapsFoodAndDrink() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("FOOD_AND_DRINK", new BigDecimal("300.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        // 300 / 3 months = 100, * 1.1 = 110, ceiling = 110
        assertThat(result).containsKey("Food & Dining");
        assertThat(result.get("Food & Dining")).isEqualByComparingTo("110");
    }

    @Test
    void getBudgetRecommendations_mapsTransportation() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("TRANSPORTATION", new BigDecimal("180.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        // 180 / 3 = 60, * 1.1 = 66, ceiling = 66
        assertThat(result).containsKey("Transportation");
        assertThat(result.get("Transportation")).isEqualByComparingTo("66");
    }

    @Test
    void getBudgetRecommendations_mapsGeneralMerchandiseToShopping() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("GENERAL_MERCHANDISE", new BigDecimal("90.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result).containsKey("Shopping");
        assertThat(result.get("Shopping")).isEqualByComparingTo("33");
    }

    @Test
    void getBudgetRecommendations_excludesTransferIn() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("TRANSFER_IN", new BigDecimal("500.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void getBudgetRecommendations_excludesTransferOut() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("TRANSFER_OUT", new BigDecimal("500.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void getBudgetRecommendations_excludesIncome() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("INCOME", new BigDecimal("3000.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void getBudgetRecommendations_unknownCategoryMapsToOther() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("SOME_UNKNOWN_CATEGORY", new BigDecimal("60.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result).containsKey("Other");
    }

    // --- getBudgetRecommendations: calculation ---

    @Test
    void getBudgetRecommendations_appliesThreeMonthAverageAndTenPercentBuffer() {
        // 300 total over 3 months = 100/month avg, +10% = 110, ceiling = 110
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("FOOD_AND_DRINK", new BigDecimal("300.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result.get("Food & Dining")).isEqualByComparingTo("110");
    }

    @Test
    void getBudgetRecommendations_roundsUpToCeilingDollar() {
        // 100 total / 3 = 33.33, * 1.1 = 36.67, ceiling = 37
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("FOOD_AND_DRINK", new BigDecimal("100.00")));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result.get("Food & Dining")).isEqualByComparingTo("37");
    }

    @Test
    void getBudgetRecommendations_combinesSameTargetCategory() {
        // GENERAL_MERCHANDISE + GENERAL_SERVICES both map to "Shopping"
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of(
                        "GENERAL_MERCHANDISE", new BigDecimal("90.00"),
                        "GENERAL_SERVICES", new BigDecimal("90.00")
                ));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        // Combined: 180 / 3 = 60, * 1.1 = 66, ceiling = 66
        assertThat(result).containsOnlyKeys("Shopping");
        assertThat(result.get("Shopping")).isEqualByComparingTo("66");
    }

    @Test
    void getBudgetRecommendations_returnsEmptyMapWhenNoSpending() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of());

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void getBudgetRecommendations_allSupportedCategoriesMap() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of(
                        "ENTERTAINMENT", new BigDecimal("30.00"),
                        "TRAVEL", new BigDecimal("30.00"),
                        "PERSONAL_CARE", new BigDecimal("30.00"),
                        "RENT_AND_UTILITIES", new BigDecimal("30.00"),
                        "MEDICAL", new BigDecimal("30.00"),
                        "HOME_IMPROVEMENT", new BigDecimal("30.00")
                ));

        Map<String, BigDecimal> result = budgetService.getBudgetRecommendations(userId);

        assertThat(result).containsKeys(
                "Entertainment", "Travel", "Personal Care",
                "Bills & Utilities", "Healthcare", "Home"
        );
    }
}

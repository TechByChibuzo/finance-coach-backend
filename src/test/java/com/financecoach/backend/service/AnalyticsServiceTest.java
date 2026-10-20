package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private AnalyticsService analyticsService;

    private UUID userId;
    private LocalDate startDate;
    private LocalDate endDate;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        startDate = LocalDate.of(2026, 1, 1);
        endDate = LocalDate.of(2026, 1, 31);
    }

    // --- transfers between the user's own accounts ---

    @Test
    void getTotalSpending_excludesTransfersAndCreditCardPayments() {
        Transaction cardPayment = expense("LOAN_PAYMENTS", 500.00);
        cardPayment.setSubcategory("LOAN_PAYMENTS_CREDIT_CARD_PAYMENT");
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(
                        expense("FOOD_AND_DRINK", 120.00),
                        expense("TRANSFER_OUT", 1000.00),
                        cardPayment));

        BigDecimal total = analyticsService.getTotalSpending(userId, startDate, endDate);

        assertThat(total).isEqualByComparingTo("120.00");
    }

    @Test
    void getTotalSpending_keepsOtherLoanPayments() {
        Transaction mortgage = expense("LOAN_PAYMENTS", 1800.00);
        mortgage.setSubcategory("LOAN_PAYMENTS_MORTGAGE_PAYMENT");
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(mortgage));

        assertThat(analyticsService.getTotalSpending(userId, startDate, endDate))
                .isEqualByComparingTo("1800.00");
    }

    @Test
    void getTotalIncome_excludesTransfersIn() {
        Transaction transferIn = expense("TRANSFER_IN", -2000.00);
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(income(3000.00), transferIn));

        assertThat(analyticsService.getTotalIncome(userId, startDate, endDate))
                .isEqualByComparingTo("3000.00");
    }

    @Test
    void getSpendingByCategory_omitsTransferCategories() {
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(expense("FOOD_AND_DRINK", 50.00), expense("TRANSFER_OUT", 400.00)));

        Map<String, BigDecimal> result = analyticsService.getSpendingByCategory(userId, startDate, endDate);

        assertThat(result).containsOnlyKeys("FOOD_AND_DRINK");
    }

    @Test
    void getTotalSpending_excludesLegacyPlaidTransferCategories() {
        Transaction legacyCardPayment = expense("Payment", 300.00);
        legacyCardPayment.setSubcategory("Credit Card");
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(expense("Transfer", 250.00), legacyCardPayment, expense("Shops", 40.00)));

        assertThat(analyticsService.getTotalSpending(userId, startDate, endDate))
                .isEqualByComparingTo("40.00");
    }

    // --- helpers ---

    private Transaction expense(String category, double amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setCategory(category);
        t.setMerchantName(category + " Merchant");
        t.setName(category + " tx");
        t.setAmount(BigDecimal.valueOf(amount));
        t.setDate(LocalDate.of(2026, 1, 15));
        t.setPlaidTransactionId(UUID.randomUUID().toString());
        t.setAccountId(UUID.randomUUID());
        return t;
    }

    private Transaction income(double amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setCategory("INCOME");
        t.setName("Paycheck");
        t.setAmount(BigDecimal.valueOf(-amount)); // negative = income in Plaid
        t.setDate(LocalDate.of(2026, 1, 1));
        t.setPlaidTransactionId(UUID.randomUUID().toString());
        t.setAccountId(UUID.randomUUID());
        return t;
    }

    // --- getSpendingByCategory ---

    @Test
    void getSpendingByCategory_groupsExpensesByCategory() {
        List<Transaction> transactions = List.of(
                expense("FOOD_AND_DRINK", 25.00),
                expense("FOOD_AND_DRINK", 15.00),
                expense("TRANSPORTATION", 40.00)
        );
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(transactions);

        Map<String, BigDecimal> result = analyticsService.getSpendingByCategory(userId, startDate, endDate);

        assertThat(result.get("FOOD_AND_DRINK")).isEqualByComparingTo("40.00");
        assertThat(result.get("TRANSPORTATION")).isEqualByComparingTo("40.00");
    }

    @Test
    void getSpendingByCategory_excludesIncomeTransactions() {
        List<Transaction> transactions = List.of(
                expense("FOOD_AND_DRINK", 50.00),
                income(2000.00)
        );
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(transactions);

        Map<String, BigDecimal> result = analyticsService.getSpendingByCategory(userId, startDate, endDate);

        assertThat(result).containsOnlyKeys("FOOD_AND_DRINK");
        assertThat(result.get("FOOD_AND_DRINK")).isEqualByComparingTo("50.00");
    }

    @Test
    void getSpendingByCategory_nullCategoryBecomesUncategorized() {
        Transaction t = expense(null, 30.00);
        t.setCategory(null);
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(t));

        Map<String, BigDecimal> result = analyticsService.getSpendingByCategory(userId, startDate, endDate);

        assertThat(result).containsKey("Uncategorized");
        assertThat(result.get("Uncategorized")).isEqualByComparingTo("30.00");
    }

    @Test
    void getSpendingByCategory_emptyTransactionsReturnsEmptyMap() {
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of());

        Map<String, BigDecimal> result = analyticsService.getSpendingByCategory(userId, startDate, endDate);

        assertThat(result).isEmpty();
    }

    // --- getTotalSpending ---

    @Test
    void getTotalSpending_sumsOnlyPositiveAmounts() {
        List<Transaction> transactions = List.of(
                expense("FOOD_AND_DRINK", 100.00),
                expense("TRANSPORTATION", 50.00),
                income(2000.00)
        );
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(transactions);

        BigDecimal result = analyticsService.getTotalSpending(userId, startDate, endDate);

        assertThat(result).isEqualByComparingTo("150.00");
    }

    @Test
    void getTotalSpending_returnsZeroWhenNoExpenses() {
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(income(3000.00)));

        BigDecimal result = analyticsService.getTotalSpending(userId, startDate, endDate);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- getTotalIncome ---

    @Test
    void getTotalIncome_sumsNegativeAmountsAsPositive() {
        List<Transaction> transactions = List.of(
                income(2000.00),
                income(500.00),
                expense("FOOD_AND_DRINK", 75.00)
        );
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(transactions);

        BigDecimal result = analyticsService.getTotalIncome(userId, startDate, endDate);

        assertThat(result).isEqualByComparingTo("2500.00");
    }

    @Test
    void getTotalIncome_returnsZeroWhenNoIncome() {
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(expense("FOOD_AND_DRINK", 50.00)));

        BigDecimal result = analyticsService.getTotalIncome(userId, startDate, endDate);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- getTopMerchants ---

    @Test
    void getTopMerchants_returnsMerchantsOrderedBySpendingDesc() {
        Transaction t1 = expense("FOOD_AND_DRINK", 10.00);
        t1.setMerchantName("Small Cafe");
        Transaction t2 = expense("FOOD_AND_DRINK", 200.00);
        t2.setMerchantName("Big Restaurant");
        Transaction t3 = expense("TRANSPORTATION", 50.00);
        t3.setMerchantName("Uber");

        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(t1, t2, t3));

        Map<String, BigDecimal> result = analyticsService.getTopMerchants(userId, startDate, endDate, 3);

        List<String> keys = List.copyOf(result.keySet());
        assertThat(keys.get(0)).isEqualTo("Big Restaurant");
        assertThat(keys.get(1)).isEqualTo("Uber");
        assertThat(keys.get(2)).isEqualTo("Small Cafe");
    }

    @Test
    void getTopMerchants_respectsLimit() {
        Transaction t1 = expense("FOOD_AND_DRINK", 100.00);
        t1.setMerchantName("Merchant A");
        Transaction t2 = expense("TRANSPORTATION", 80.00);
        t2.setMerchantName("Merchant B");
        Transaction t3 = expense("SHOPPING", 60.00);
        t3.setMerchantName("Merchant C");

        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(t1, t2, t3));

        Map<String, BigDecimal> result = analyticsService.getTopMerchants(userId, startDate, endDate, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void getTopMerchants_usesMerchantNameFallsBackToName() {
        Transaction t = expense("FOOD_AND_DRINK", 50.00);
        t.setMerchantName(null);
        t.setName("Fallback Name");

        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(t));

        Map<String, BigDecimal> result = analyticsService.getTopMerchants(userId, startDate, endDate, 5);

        assertThat(result).containsKey("Fallback Name");
    }

    // --- trend, monthly summary, month comparison ---

    @Test
    void getSpendingTrend_sumsEachDay_inDateOrder() {
        Transaction day2a = expense("FOOD_AND_DRINK", 10.00);
        day2a.setDate(LocalDate.of(2026, 1, 2));
        Transaction day2b = expense("TRANSPORTATION", 5.50);
        day2b.setDate(LocalDate.of(2026, 1, 2));
        Transaction day1 = expense("FOOD_AND_DRINK", 20.00);
        day1.setDate(LocalDate.of(2026, 1, 1));
        when(transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate))
                .thenReturn(List.of(day2a, income(100), day1, day2b));

        Map<LocalDate, BigDecimal> trend = analyticsService.getSpendingTrend(userId, startDate, endDate);

        assertThat(trend.keySet()).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
        assertThat(trend.get(LocalDate.of(2026, 1, 1))).isEqualByComparingTo("20.00");
        assertThat(trend.get(LocalDate.of(2026, 1, 2))).isEqualByComparingTo("15.50");
    }

    @Test
    void getMonthlySummary_coversTheWholeCalendarMonth() {
        LocalDate monthStart = LocalDate.of(2026, 2, 1);
        LocalDate monthEnd = LocalDate.of(2026, 2, 28);
        when(transactionRepository.findByUserIdAndDateBetween(userId, monthStart, monthEnd))
                .thenReturn(List.of(expense("FOOD_AND_DRINK", 120.00), expense("TRAVEL", 80.00), income(1000)));

        Map<String, Object> summary = analyticsService.getMonthlySummary(userId, LocalDate.of(2026, 2, 14));

        assertThat(summary.get("month")).isEqualTo("2026-02-14");
        assertThat((BigDecimal) summary.get("totalSpending")).isEqualByComparingTo("200.00");
        assertThat((BigDecimal) summary.get("totalIncome")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) summary.get("netCashFlow")).isEqualByComparingTo("800.00");
        assertThat(summary.get("transactionCount")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> categories = (Map<String, BigDecimal>) summary.get("categoryBreakdown");
        assertThat(categories).containsOnlyKeys("FOOD_AND_DRINK", "TRAVEL");
        assertThat((Map<?, ?>) summary.get("topMerchants")).hasSize(2);
    }

    @Test
    void compareMonths_reportsTheChangeAndPercentage() {
        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate lastMonth = thisMonth.minusMonths(1);
        when(transactionRepository.findByUserIdAndDateBetween(userId, thisMonth, thisMonth.withDayOfMonth(thisMonth.lengthOfMonth())))
                .thenReturn(List.of(expense("FOOD_AND_DRINK", 150.00)));
        when(transactionRepository.findByUserIdAndDateBetween(userId, lastMonth, lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())))
                .thenReturn(List.of(expense("FOOD_AND_DRINK", 100.00)));

        Map<String, Object> comparison = analyticsService.compareMonths(userId);

        assertThat((BigDecimal) comparison.get("spendingChange")).isEqualByComparingTo("50.00");
        assertThat(comparison.get("spendingChangePercentage")).isEqualTo(50.0);
        assertThat(comparison).containsKeys("currentMonth", "previousMonth");
    }

    @Test
    void compareMonths_withNoSpendingLastMonth_reportsZeroPercent() {
        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate lastMonth = thisMonth.minusMonths(1);
        when(transactionRepository.findByUserIdAndDateBetween(userId, thisMonth, thisMonth.withDayOfMonth(thisMonth.lengthOfMonth())))
                .thenReturn(List.of(expense("FOOD_AND_DRINK", 150.00)));
        when(transactionRepository.findByUserIdAndDateBetween(userId, lastMonth, lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())))
                .thenReturn(List.of());

        Map<String, Object> comparison = analyticsService.compareMonths(userId);

        assertThat((BigDecimal) comparison.get("spendingChange")).isEqualByComparingTo("150.00");
        assertThat(comparison.get("spendingChangePercentage")).isEqualTo(0.0);
    }
}

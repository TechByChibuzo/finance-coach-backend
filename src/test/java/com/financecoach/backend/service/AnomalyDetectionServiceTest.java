package com.financecoach.backend.service;

import com.financecoach.backend.exception.AnomalyNotFoundException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.SpendingAnomaly;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.SpendingAnomalyRepository;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private SpendingAnomalyRepository anomalyRepository;

    private AnomalyDetectionService anomalyDetectionService;

    // A fixed "today", late in the month, so results don't depend on the day the tests run
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    private UUID ownerId;
    private UUID anomalyId;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        anomalyId = UUID.randomUUID();
        anomalyDetectionService = serviceOn(TODAY);
    }

    private AnomalyDetectionService serviceOn(LocalDate today) {
        return new AnomalyDetectionService(transactionRepository, anomalyRepository,
                Clock.fixed(today.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    // --- detectAnomalies ---

    @Test
    void detectAnomalies_storesTheAnomalyForTheWholeMonth() {
        stubSpending(new BigDecimal("400.00"));
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(
                ownerId, "FOOD_AND_DRINK", monthStart()))
                .thenReturn(List.of());
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SpendingAnomaly> anomalies = anomalyDetectionService.detectAnomalies(ownerId);

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getPeriodStart()).isEqualTo(monthStart());
        assertThat(anomalies.get(0).getPeriodEnd()).isEqualTo(monthStart().withDayOfMonth(monthStart().lengthOfMonth()));
        assertThat(anomalies.get(0).getSeverity()).isEqualTo("HIGH");
    }

    @Test
    void detectAnomalies_runAgain_updatesTheExistingAnomalyInsteadOfDuplicating() {
        SpendingAnomaly existing = new SpendingAnomaly(
                ownerId, "FOOD_AND_DRINK", new BigDecimal("240.00"), new BigDecimal("200.00"),
                new BigDecimal("16.33"), new BigDecimal("2.45"), "MEDIUM",
                monthStart(), monthStart().withDayOfMonth(monthStart().lengthOfMonth()));
        stubSpending(new BigDecimal("400.00"));
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(
                ownerId, "FOOD_AND_DRINK", monthStart()))
                .thenReturn(List.of(existing));
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        anomalyDetectionService.detectAnomalies(ownerId);

        ArgumentCaptor<SpendingAnomaly> saved = ArgumentCaptor.forClass(SpendingAnomaly.class);
        verify(anomalyRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(existing.getCurrentSpend()).isEqualByComparingTo("400.00");
        assertThat(existing.getSeverity()).isEqualTo("HIGH");
    }

    @Test
    void detectAnomalies_whenSeverityIncreases_showsAReviewedAnomalyAgain() {
        SpendingAnomaly existing = new SpendingAnomaly(
                ownerId, "FOOD_AND_DRINK", new BigDecimal("240.00"), new BigDecimal("200.00"),
                new BigDecimal("16.33"), new BigDecimal("2.45"), "MEDIUM",
                monthStart(), monthStart().withDayOfMonth(monthStart().lengthOfMonth()));
        existing.markAsReviewed();
        stubSpending(new BigDecimal("400.00"));
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(
                ownerId, "FOOD_AND_DRINK", monthStart()))
                .thenReturn(List.of(existing));
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        anomalyDetectionService.detectAnomalies(ownerId);

        assertThat(existing.getIsReviewed()).isFalse();
    }

    @Test
    void detectAnomalies_withLegacyDuplicates_keepsTheNewestAndDeletesTheRest() {
        LocalDate legacyEnd = TODAY.plusDays(1);
        SpendingAnomaly newest = new SpendingAnomaly(
                ownerId, "FOOD_AND_DRINK", new BigDecimal("240.00"), new BigDecimal("200.00"),
                new BigDecimal("16.33"), new BigDecimal("2.45"), "MEDIUM", monthStart(), legacyEnd);
        SpendingAnomaly older = new SpendingAnomaly(
                ownerId, "FOOD_AND_DRINK", new BigDecimal("230.00"), new BigDecimal("200.00"),
                new BigDecimal("16.33"), new BigDecimal("1.84"), "LOW", monthStart(), legacyEnd.minusDays(1));
        stubSpending(new BigDecimal("400.00"));
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(
                ownerId, "FOOD_AND_DRINK", monthStart()))
                .thenReturn(List.of(newest, older));
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SpendingAnomaly> anomalies = anomalyDetectionService.detectAnomalies(ownerId);

        assertThat(anomalies).containsExactly(newest);
        assertThat(newest.getPeriodEnd()).isEqualTo(monthStart().withDayOfMonth(monthStart().lengthOfMonth()));
        verify(anomalyRepository).deleteAll(List.of(older));
    }

    @Test
    void detectAnomalies_withoutHistory_findsNothing() {
        LocalDate monthStart = monthStart();
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, TODAY))
                .thenReturn(List.of(food(TODAY, "500.00")));
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(List.of());

        assertThat(anomalyDetectionService.detectAnomalies(ownerId)).isEmpty();
    }

    @Test
    void detectAnomalies_ignoresSmallSpend_flatHistory_andNormalSpend() {
        LocalDate monthStart = monthStart();
        Transaction tinyCoffee = categorized("COFFEE", TODAY, "3.00");
        Transaction flatRent = categorized("RENT", TODAY, "2000.00");
        Transaction normalFood = food(TODAY, "205.00");
        Transaction uncategorized = categorized(null, TODAY, "999.00");
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, TODAY))
                .thenReturn(List.of(tinyCoffee, flatRent, normalFood, uncategorized));
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(List.of(
                        categorized("COFFEE", monthStart.minusMonths(1).withDayOfMonth(5), "4.00"),
                        categorized("COFFEE", monthStart.minusMonths(2).withDayOfMonth(5), "4.00"),
                        categorized("RENT", monthStart.minusMonths(1).withDayOfMonth(1), "2000.00"),
                        categorized("RENT", monthStart.minusMonths(2).withDayOfMonth(1), "2000.00"),
                        food(monthStart.minusMonths(1).withDayOfMonth(10), "200.00"),
                        food(monthStart.minusMonths(2).withDayOfMonth(10), "220.00"),
                        food(monthStart.minusMonths(3).withDayOfMonth(10), "180.00")));

        assertThat(anomalyDetectionService.detectAnomalies(ownerId)).isEmpty();
        verify(anomalyRepository, never()).save(any());
    }

    @Test
    void detectAnomalies_aMonthWithNoSpendingInACategory_countsAsZero() {
        // Food: $200 and $220, with a month of none in between (the user still had other spending).
        // Leaving that month out made $240 look far above normal (mean 210, sd 10: z = 3).
        LocalDate monthStart = monthStart();
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, TODAY))
                .thenReturn(List.of(food(TODAY, "240.00")));
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(List.of(
                        food(monthStart.minusMonths(1).withDayOfMonth(10), "200.00"),
                        categorized("SHOPPING", monthStart.minusMonths(2).withDayOfMonth(10), "50.00"),
                        food(monthStart.minusMonths(3).withDayOfMonth(10), "220.00")));

        assertThat(anomalyDetectionService.detectAnomalies(ownerId)).isEmpty();
    }

    @Test
    void detectAnomalies_monthsBeforeTheUsersDataStarts_dontCountAsZero() {
        // Only two months of data so far: the four empty months before them aren't "spent $0"
        LocalDate monthStart = monthStart();
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, TODAY))
                .thenReturn(List.of(food(TODAY, "240.00")));
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(List.of(
                        food(monthStart.minusMonths(1).withDayOfMonth(10), "200.00"),
                        food(monthStart.minusMonths(2).withDayOfMonth(10), "220.00")));
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SpendingAnomaly> anomalies = anomalyDetectionService.detectAnomalies(ownerId);

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getAverageSpend()).isEqualByComparingTo("210");
    }

    @Test
    void detectAnomalies_aCategoryWithLittlePastSpending_spikingNow_isFlagged() {
        LocalDate monthStart = monthStart();
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, TODAY))
                .thenReturn(List.of(categorized("TRAVEL", TODAY, "900.00")));
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(List.of(
                        categorized("TRAVEL", monthStart.minusMonths(1).withDayOfMonth(5), "100.00"),
                        food(monthStart.minusMonths(2).withDayOfMonth(10), "200.00"),
                        food(monthStart.minusMonths(3).withDayOfMonth(10), "200.00")));
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(anomalyDetectionService.detectAnomalies(ownerId))
                .extracting(SpendingAnomaly::getCategory).containsExactly("TRAVEL");
    }

    @Test
    void detectAnomalies_earlyInTheMonth_comparesWithWhatWasSpentByTheSameDay() {
        // On the 5th, $120 of food is far above the ~$50 usually spent by the 5th, even though
        // it's well under a usual full month (~$200). Comparing with full months hid it.
        LocalDate fifth = LocalDate.of(2026, 10, 5);
        AnomalyDetectionService service = serviceOn(fifth);
        LocalDate monthStart = fifth.withDayOfMonth(1);
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, fifth))
                .thenReturn(List.of(food(fifth.withDayOfMonth(2), "120.00")));
        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(List.of(
                        food(monthStart.minusMonths(1).withDayOfMonth(3), "50.00"),
                        food(monthStart.minusMonths(1).withDayOfMonth(20), "150.00"),
                        food(monthStart.minusMonths(2).withDayOfMonth(3), "60.00"),
                        food(monthStart.minusMonths(2).withDayOfMonth(20), "160.00"),
                        food(monthStart.minusMonths(3).withDayOfMonth(3), "40.00"),
                        food(monthStart.minusMonths(3).withDayOfMonth(20), "140.00")));
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SpendingAnomaly> anomalies = service.detectAnomalies(ownerId);

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getAverageSpend()).isEqualByComparingTo("50");
        assertThat(anomalies.get(0).getSeverity()).isEqualTo("HIGH");
    }

    @Test
    void detectAnomalies_gradesSeverityByZScore() {
        stubSpending(new BigDecimal("235.00")); // mean 200, std ~16.3 -> z ~2.1
        when(anomalyRepository.findByUserIdAndCategoryAndPeriodStartOrderByDetectedAtDesc(
                ownerId, "FOOD_AND_DRINK", monthStart())).thenReturn(List.of());
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(anomalyDetectionService.detectAnomalies(ownerId).get(0).getSeverity()).isEqualTo("MEDIUM");

        stubSpending(new BigDecimal("226.00")); // z ~1.6
        assertThat(anomalyDetectionService.detectAnomalies(ownerId).get(0).getSeverity()).isEqualTo("LOW");
    }

    @Test
    void queries_delegateToTheRepository() {
        List<SpendingAnomaly> all = List.of(anomalyOwnedBy(ownerId));
        when(anomalyRepository.findByUserIdOrderByDetectedAtDesc(ownerId)).thenReturn(all);
        when(anomalyRepository.findByUserIdAndIsReviewedFalseOrderByDetectedAtDesc(ownerId)).thenReturn(all);
        when(anomalyRepository.countByUserIdAndIsReviewedFalse(ownerId)).thenReturn(1L);

        assertThat(anomalyDetectionService.getAllAnomalies(ownerId)).isSameAs(all);
        assertThat(anomalyDetectionService.getUnreviewedAnomalies(ownerId)).isSameAs(all);
        assertThat(anomalyDetectionService.getUnreviewedCount(ownerId)).isEqualTo(1L);
    }

    // --- markAsReviewed ---

    @Test
    void markAsReviewed_byOwner_marksReviewed() {
        SpendingAnomaly anomaly = anomalyOwnedBy(ownerId);
        when(anomalyRepository.findById(anomalyId)).thenReturn(Optional.of(anomaly));

        anomalyDetectionService.markAsReviewed(anomalyId, ownerId);

        assertThat(anomaly.getIsReviewed()).isTrue();
        verify(anomalyRepository).save(anomaly);
    }

    @Test
    void markAsReviewed_byAnotherUser_isRejected() {
        SpendingAnomaly anomaly = anomalyOwnedBy(ownerId);
        when(anomalyRepository.findById(anomalyId)).thenReturn(Optional.of(anomaly));

        assertThatThrownBy(() -> anomalyDetectionService.markAsReviewed(anomalyId, UUID.randomUUID()))
                .isInstanceOf(UnauthorizedAccessException.class);

        assertThat(anomaly.getIsReviewed()).isFalse();
        verify(anomalyRepository, never()).save(any());
    }

    @Test
    void markAsReviewed_unknownAnomaly_throwsNotFound() {
        when(anomalyRepository.findById(anomalyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> anomalyDetectionService.markAsReviewed(anomalyId, ownerId))
                .isInstanceOf(AnomalyNotFoundException.class);
    }

    private LocalDate monthStart() {
        return TODAY.withDayOfMonth(1);
    }

    /** Three past months of ~$200 food spending, and the given amount so far this month */
    private void stubSpending(BigDecimal currentMonthSpend) {
        LocalDate monthStart = monthStart();
        List<Transaction> history = List.of(
                food(monthStart.minusMonths(1).withDayOfMonth(10), "200.00"),
                food(monthStart.minusMonths(2).withDayOfMonth(10), "220.00"),
                food(monthStart.minusMonths(3).withDayOfMonth(10), "180.00"));

        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, TODAY))
                .thenReturn(List.of(food(TODAY, currentMonthSpend.toPlainString())));
        when(transactionRepository.findByUserIdAndDateBetween(
                ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(history);
    }

    private Transaction categorized(String category, LocalDate date, String amount) {
        Transaction t = food(date, amount);
        t.setCategory(category);
        return t;
    }

    private Transaction food(LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.setUserId(ownerId);
        t.setCategory("FOOD_AND_DRINK");
        t.setAmount(new BigDecimal(amount));
        t.setDate(date);
        return t;
    }

    private SpendingAnomaly anomalyOwnedBy(UUID userId) {
        return new SpendingAnomaly(
                userId, "FOOD_AND_DRINK",
                new BigDecimal("500.00"), new BigDecimal("200.00"),
                new BigDecimal("50.00"), new BigDecimal("6.00"), "HIGH",
                TODAY.withDayOfMonth(1), TODAY
        );
    }

    @Test
    void detectForAllUsers_checksEveryUser_andCarriesOnPastAFailure() {
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        org.mockito.Mockito.when(transactionRepository.findDistinctUserIds()).thenReturn(List.of(broken, fine));
        org.mockito.Mockito.when(transactionRepository.findByUserIdAndDateBetween(
                        org.mockito.ArgumentMatchers.eq(broken), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db hiccup"));

        anomalyDetectionService.detectForAllUsers();

        org.mockito.Mockito.verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).findByUserIdAndDateBetween(
                org.mockito.ArgumentMatchers.eq(fine), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}

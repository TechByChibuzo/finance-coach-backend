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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @InjectMocks
    private AnomalyDetectionService anomalyDetectionService;

    private UUID ownerId;
    private UUID anomalyId;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        anomalyId = UUID.randomUUID();
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
        LocalDate legacyEnd = LocalDate.now().plusDays(1);
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
        return LocalDate.now().withDayOfMonth(1);
    }

    /** Three past months of ~$200 food spending, and the given amount so far this month */
    private void stubSpending(BigDecimal currentMonthSpend) {
        LocalDate monthStart = monthStart();
        List<Transaction> history = List.of(
                food(monthStart.minusMonths(1).withDayOfMonth(10), "200.00"),
                food(monthStart.minusMonths(2).withDayOfMonth(10), "220.00"),
                food(monthStart.minusMonths(3).withDayOfMonth(10), "180.00"));

        when(transactionRepository.findByUserIdAndDateBetween(ownerId, monthStart, LocalDate.now()))
                .thenReturn(List.of(food(LocalDate.now(), currentMonthSpend.toPlainString())));
        when(transactionRepository.findByUserIdAndDateBetween(
                ownerId, monthStart.minusMonths(6), monthStart.minusDays(1)))
                .thenReturn(history);
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
                LocalDate.now().withDayOfMonth(1), LocalDate.now()
        );
    }
}

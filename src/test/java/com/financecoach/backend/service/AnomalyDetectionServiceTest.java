package com.financecoach.backend.service;

import com.financecoach.backend.exception.AnomalyNotFoundException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.SpendingAnomaly;
import com.financecoach.backend.repository.SpendingAnomalyRepository;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    private SpendingAnomaly anomalyOwnedBy(UUID userId) {
        return new SpendingAnomaly(
                userId, "FOOD_AND_DRINK",
                new BigDecimal("500.00"), new BigDecimal("200.00"),
                new BigDecimal("50.00"), new BigDecimal("6.00"), "HIGH",
                LocalDate.now().withDayOfMonth(1), LocalDate.now()
        );
    }
}

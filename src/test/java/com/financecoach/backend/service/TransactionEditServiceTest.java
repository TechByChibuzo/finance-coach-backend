package com.financecoach.backend.service;

import com.financecoach.backend.dto.TransactionSplitRequest.Part;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class TransactionEditServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private VectorStoreService vectorStoreService;

    private TransactionEditService service;
    private UUID userId;
    private Transaction costco;

    @BeforeEach
    void setUp() {
        service = new TransactionEditService(transactionRepository, vectorStoreService);
        userId = UUID.randomUUID();
        costco = new Transaction();
        costco.setId(UUID.randomUUID());
        costco.setUserId(userId);
        costco.setAccountId(UUID.randomUUID());
        costco.setPlaidTransactionId("plaid-costco");
        costco.setDate(LocalDate.of(2026, 10, 12));
        costco.setAmount(new BigDecimal("150.00"));
        costco.setMerchantName("Costco");
        costco.setName("COSTCO WHSE #123");
        costco.setCategory("GENERAL_MERCHANDISE");
        costco.setLogoUrl("https://logos/costco.png");
        costco.setImportBatchId(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(transactionRepository.findById(costco.getId())).thenReturn(Optional.of(costco));
        org.mockito.Mockito.lenient().when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(transactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- notes and hiding ---

    @Test
    void updateDetails_savesTheNoteTrimmed_andABlankNoteClearsIt() {
        service.updateDetails(userId, costco.getId(), "  Birthday party supplies ", null);
        assertThat(costco.getNotes()).isEqualTo("Birthday party supplies");

        service.updateDetails(userId, costco.getId(), "   ", null);
        assertThat(costco.getNotes()).isNull();
        verify(vectorStoreService, never()).removeTransaction(any());
    }

    @Test
    void updateDetails_hiding_removesItFromTheCoach_andUnhidingAddsItBack() {
        service.updateDetails(userId, costco.getId(), null, true);
        assertThat(costco.getHidden()).isTrue();
        verify(vectorStoreService).removeTransaction("plaid-costco");

        service.updateDetails(userId, costco.getId(), null, false);
        verify(vectorStoreService).indexTransactionsAsync(List.of(costco));
    }

    @Test
    void updateDetails_ofAnotherUsersTransaction_isRejected() {
        costco.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.updateDetails(userId, costco.getId(), "mine now", null))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(transactionRepository, never()).save(any());
    }

    // --- splitting ---

    @Test
    void split_createsAPartPerCategory_andLeavesTheOriginalOutOfTotals() {
        List<Transaction> parts = service.split(userId, costco.getId(), List.of(
                new Part("FOOD_AND_DRINK", new BigDecimal("100.00")),
                new Part("HOME_IMPROVEMENT", new BigDecimal("50.00"))));

        assertThat(parts).hasSize(2);
        Transaction groceries = parts.get(0);
        assertThat(groceries.getAmount()).isEqualByComparingTo("100.00");
        assertThat(groceries.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(groceries.getCategoryOverridden()).isTrue();
        assertThat(groceries.getSplitFromId()).isEqualTo(costco.getId());
        assertThat(groceries.getPlaidTransactionId()).isEqualTo("plaid-costco#split-1");
        assertThat(groceries.getMerchantName()).isEqualTo("Costco");
        assertThat(groceries.getDate()).isEqualTo(costco.getDate());
        assertThat(groceries.getAccountId()).isEqualTo(costco.getAccountId());
        assertThat(groceries.getLogoUrl()).isEqualTo("https://logos/costco.png");
        assertThat(groceries.getImportBatchId()).isEqualTo(costco.getImportBatchId());
        assertThat(parts.get(1).getCategory()).isEqualTo("HOME_IMPROVEMENT");
        assertThat(parts.get(1).getPlaidTransactionId()).isEqualTo("plaid-costco#split-2");

        assertThat(costco.getSplit()).isTrue();
        assertThat(TransactionFilters.isSpending(costco)).isFalse();
        assertThat(TransactionFilters.isSpending(groceries)).isTrue();
        verify(transactionRepository).deleteBySplitFromId(costco.getId()); // replaces an earlier split
        verify(vectorStoreService).removeTransaction("plaid-costco");
        verify(vectorStoreService).indexTransactionsAsync(parts);
    }

    @Test
    void split_partsThatDontAddUp_areRefused() {
        assertThatThrownBy(() -> service.split(userId, costco.getId(), List.of(
                new Part("FOOD_AND_DRINK", new BigDecimal("100.00")),
                new Part("HOME_IMPROVEMENT", new BigDecimal("40.00")))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The parts add up to 140.00 but the transaction is 150.00.");
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void split_aZeroNegativeOrFractionalCentPart_isRefused() {
        for (String amount : List.of("0", "-10.00", "10.001")) {
            assertThatThrownBy(() -> service.split(userId, costco.getId(), List.of(
                    new Part("FOOD_AND_DRINK", new BigDecimal(amount)),
                    new Part("HOME_IMPROVEMENT", new BigDecimal("150.00")))))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void split_aRefund_isSplitIntoNegativeParts() {
        costco.setAmount(new BigDecimal("-30.00"));

        List<Transaction> parts = service.split(userId, costco.getId(), List.of(
                new Part("FOOD_AND_DRINK", new BigDecimal("-20.00")),
                new Part("HOME_IMPROVEMENT", new BigDecimal("-10.00"))));

        assertThat(parts).extracting(Transaction::getAmount).map(BigDecimal::toPlainString).containsExactly("-20.00", "-10.00");
    }

    @Test
    void split_anUnknownCategory_orAPartOfASplit_isRefused() {
        assertThatThrownBy(() -> service.split(userId, costco.getId(), List.of(
                new Part("NOT_A_CATEGORY", new BigDecimal("100.00")),
                new Part("FOOD_AND_DRINK", new BigDecimal("50.00")))))
                .isInstanceOf(ValidationException.class);

        costco.setSplitFromId(UUID.randomUUID());
        assertThatThrownBy(() -> service.split(userId, costco.getId(), List.of(
                new Part("FOOD_AND_DRINK", new BigDecimal("100.00")),
                new Part("HOME_IMPROVEMENT", new BigDecimal("50.00")))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already part of a split");
    }

    @Test
    void unsplit_fromAPart_putsTheOriginalBackTogether() {
        costco.setSplit(true);
        Transaction part = new Transaction();
        part.setId(UUID.randomUUID());
        part.setUserId(userId);
        part.setSplitFromId(costco.getId());
        when(transactionRepository.findById(part.getId())).thenReturn(Optional.of(part));

        Transaction restored = service.unsplit(userId, part.getId());

        assertThat(restored).isSameAs(costco);
        assertThat(costco.getSplit()).isFalse();
        verify(transactionRepository).deleteBySplitFromId(costco.getId());
        ArgumentCaptor<List<Transaction>> indexed = ArgumentCaptor.forClass(List.class);
        verify(vectorStoreService).indexTransactionsAsync(indexed.capture());
        assertThat(indexed.getValue()).containsExactly(costco);
    }

    @Test
    void unsplit_aHiddenTransaction_staysOutOfTheCoach() {
        costco.setSplit(true);
        costco.setHidden(true);

        service.unsplit(userId, costco.getId());

        verify(vectorStoreService, never()).indexTransactionsAsync(any());
    }

    @Test
    void getParts_listsWhatTheTransactionWasSplitInto() {
        Transaction part = new Transaction();
        when(transactionRepository.findBySplitFromIdOrderByAmountDesc(costco.getId())).thenReturn(List.of(part));

        assertThat(service.getParts(userId, costco.getId())).containsExactly(part);
    }
}

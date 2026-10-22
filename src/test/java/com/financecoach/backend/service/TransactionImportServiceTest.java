package com.financecoach.backend.service;

import com.financecoach.backend.dto.imports.TransactionImportRequest;
import com.financecoach.backend.dto.imports.TransactionImportResponse;
import com.financecoach.backend.model.Transaction;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionImportServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionCategorizer categorizer;
    @Mock private VectorStoreService vectorStoreService;

    @InjectMocks
    private TransactionImportService importService;

    private UUID userId;
    private final List<String> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        lenient().when(transactionRepository.findExistingPlaidTransactionIds(any())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.stream().filter(stored::contains).toList();
        });
        lenient().when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Transaction> saved = inv.getArgument(0);
            saved.forEach(t -> stored.add(t.getPlaidTransactionId()));
            return saved;
        });
        lenient().when(categorizer.categorize(anyList())).thenAnswer(inv -> {
            List<?> inputs = inv.getArgument(0);
            return inputs.stream().map(i -> new TransactionCategorizer.Category("FOOD_AND_DRINK", null)).toList();
        });
    }

    private static TransactionImportRequest.Row row(String date, String description, String amount) {
        return new TransactionImportRequest.Row(LocalDate.parse(date), description, new BigDecimal(amount));
    }

    @Test
    void importsRows_withCategoriesAccountAndBatch_thenIndexesThem() {
        TransactionImportResponse response = importService.importTransactions(userId, new TransactionImportRequest(
                " Chase Checking ", List.of(row("2026-10-01", "Starbucks ", "4.50"), row("2026-10-02", "Payroll", "-2000"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(saved.capture());
        Transaction first = saved.getValue().get(0);
        assertThat(response.imported()).isEqualTo(2);
        assertThat(response.duplicates()).isZero();
        assertThat(first.getUserId()).isEqualTo(userId);
        assertThat(first.getName()).isEqualTo("Starbucks");
        assertThat(first.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(first.getImportAccountName()).isEqualTo("Chase Checking");
        assertThat(first.getImportBatchId()).isEqualTo(response.batchId());
        assertThat(first.getPlaidTransactionId()).startsWith("csv_");
        assertThat(first.getAccountId()).isEqualTo(TransactionImportService.importedAccountId(userId, "Chase Checking"));
        verify(vectorStoreService).indexTransactionsAsync(saved.getValue());
    }

    @Test
    void importingTheSameFileTwice_skipsWhatIsAlreadyThere() {
        TransactionImportRequest request = new TransactionImportRequest("Chase", List.of(
                row("2026-10-01", "Coffee", "4.50"), row("2026-10-03", "Lunch", "12")));

        importService.importTransactions(userId, request);
        TransactionImportResponse again = importService.importTransactions(userId, request);

        assertThat(again.imported()).isZero();
        assertThat(again.duplicates()).isEqualTo(2);
        assertThat(stored).hasSize(2);
    }

    @Test
    void identicalRowsInOneFile_bothImport() {
        TransactionImportResponse response = importService.importTransactions(userId, new TransactionImportRequest(
                "Chase", List.of(row("2026-10-01", "Coffee", "4.50"), row("2026-10-01", "COFFEE", "4.5"))));

        assertThat(response.imported()).isEqualTo(2);
        assertThat(stored).doesNotHaveDuplicates();
    }

    @Test
    void sameRowsUnderDifferentAccountsOrUsers_areDifferentTransactions() {
        List<TransactionImportRequest.Row> rows = List.of(row("2026-10-01", "Coffee", "4.50"));
        importService.importTransactions(userId, new TransactionImportRequest("Chase", rows));
        importService.importTransactions(userId, new TransactionImportRequest("Amex", rows));
        importService.importTransactions(UUID.randomUUID(), new TransactionImportRequest("Chase", rows));

        assertThat(stored).hasSize(3).doesNotHaveDuplicates();
        assertThat(TransactionImportService.importedAccountId(userId, "Chase"))
                .isEqualTo(TransactionImportService.importedAccountId(userId, "chase"));
    }

    @Test
    void allDuplicates_savesNothingAndSkipsCategorizing() {
        TransactionImportRequest request = new TransactionImportRequest("Chase", List.of(row("2026-10-01", "Coffee", "4.50")));
        importService.importTransactions(userId, request);
        org.mockito.Mockito.clearInvocations(categorizer, transactionRepository, vectorStoreService);

        TransactionImportResponse again = importService.importTransactions(userId, request);

        assertThat(again.imported()).isZero();
        verify(categorizer, never()).categorize(anyList());
        verify(transactionRepository, never()).saveAll(anyList());
        verify(vectorStoreService, never()).indexTransactionsAsync(anyList());
    }

    @Test
    void undoImport_deletesOnlyThatUsersBatch() {
        UUID batchId = UUID.randomUUID();
        when(transactionRepository.deleteImportBatch(userId, batchId)).thenReturn(7);

        assertThat(importService.undoImport(userId, batchId)).isEqualTo(7);
    }
}

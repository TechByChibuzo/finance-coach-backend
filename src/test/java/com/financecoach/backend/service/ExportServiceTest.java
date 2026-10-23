package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private S3Service s3Service;

    @InjectMocks
    private ExportService exportService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void exportTransactionsCSV_uploadsACsvUnderTheUsersPrefix_andReturnsADownloadLink() {
        Transaction plain = transaction("Uber", "TRANSPORTATION", "12.50", "USD");
        Transaction tricky = transaction("Joe's \"Best\" Pizza, Inc", "FOOD_AND_DRINK", "30.00", null);
        Transaction noMerchant = transaction(null, null, "-100.00", "USD");
        noMerchant.setName("Payroll");
        Transaction splitOriginal = transaction("Costco", "GENERAL_MERCHANDISE", "150.00", "USD");
        splitOriginal.setSplit(true); // its parts are exported instead
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(plain, tricky, splitOriginal, noMerchant));
        when(s3Service.generatePresignedUrl(anyString())).thenReturn("https://s3.example/download");

        String url = exportService.exportTransactionsCSV(userId);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).uploadFile(key.capture(), body.capture(), eq("text/csv"));
        assertThat(url).isEqualTo("https://s3.example/download");
        assertThat(key.getValue()).startsWith("exports/" + userId + "/transactions-").endsWith(".csv");

        String[] lines = new String(body.getValue(), StandardCharsets.UTF_8).split("\n");
        assertThat(lines[0]).isEqualTo("Date,Merchant,Category,Amount,Currency");
        assertThat(lines[1]).isEqualTo("2026-09-15,Uber,TRANSPORTATION,12.50,USD");
        // Commas and quotes are escaped; missing currency defaults to USD
        assertThat(lines[2]).isEqualTo("2026-09-15,\"Joe's \"\"Best\"\" Pizza, Inc\",FOOD_AND_DRINK,30.00,USD");
        // Falls back to the transaction name; a missing category is an empty cell
        assertThat(lines[3]).isEqualTo("2026-09-15,Payroll,,-100.00,USD");
    }

    @Test
    void exportTransactionsCSV_textThatASpreadsheetWouldRunAsAFormula_isExportedAsText() {
        // Merchant names come from banks and CSV imports; Excel runs a cell starting with = + - @ as a formula
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(
                transaction("=HYPERLINK(\"http://evil\")", "+cmd", "1.00", "USD"),
                transaction("-2+3", "@SUM(A1)", "-2.00", "USD")));

        exportService.exportTransactionsCSV(userId);

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).uploadFile(anyString(), body.capture(), eq("text/csv"));
        String[] lines = new String(body.getValue(), StandardCharsets.UTF_8).split("\n");
        assertThat(lines[1]).isEqualTo("2026-09-15,\"'=HYPERLINK(\"\"http://evil\"\")\",'+cmd,1.00,USD");
        // Amounts stay numbers
        assertThat(lines[2]).isEqualTo("2026-09-15,'-2+3,'@SUM(A1),-2.00,USD");
    }

    private Transaction transaction(String merchant, String category, String amount, String currency) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.of(2026, 9, 15));
        t.setMerchantName(merchant);
        t.setCategory(category);
        t.setAmount(new BigDecimal(amount));
        t.setCurrencyCode(currency);
        return t;
    }
}

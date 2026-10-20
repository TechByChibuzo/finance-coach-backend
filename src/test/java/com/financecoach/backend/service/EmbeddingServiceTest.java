package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @InjectMocks
    private EmbeddingService embeddingService;

    @Test
    void buildTransactionText_describesAPurchase() {
        String text = embeddingService.buildTransactionText(transaction("42.50", "Starbucks", "FOOD_AND_DRINK"));

        assertThat(text)
                .contains("$42.50 at Starbucks on 2026-09-15")
                .contains("Category: FOOD_AND_DRINK")
                .contains("purchase/debit");
    }

    @Test
    void buildTransactionText_forRefundsWithoutMerchantOrCategory_usesFallbacks() {
        Transaction refund = transaction("-10.00", null, null);
        refund.setName("AMAZON REFUND");

        String text = embeddingService.buildTransactionText(refund);

        assertThat(text).contains("at AMAZON REFUND").contains("Category: Uncategorized").contains("credit/refund");
    }

    @Test
    void embedTransaction_embedsTheDescriptionText() {
        float[] vector = {0.1f, 0.2f};
        when(embeddingModel.embed(anyString())).thenReturn(vector);

        assertThat(embeddingService.embedTransaction(transaction("5.00", "Uber", "TRANSPORTATION"))).isSameAs(vector);
        assertThat(embeddingService.embedText("question")).isSameAs(vector);
    }

    private Transaction transaction(String amount, String merchant, String category) {
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal(amount));
        t.setMerchantName(merchant);
        t.setCategory(category);
        t.setDate(LocalDate.of(2026, 9, 15));
        t.setAccountId(UUID.randomUUID());
        t.setPlaidTransactionId("tx-1");
        return t;
    }
}

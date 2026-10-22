package com.financecoach.backend.service;

import com.financecoach.backend.exception.AiServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionCategorizerTest {

    @Mock
    private ClaudeService claudeService;

    @InjectMocks
    private TransactionCategorizer categorizer;

    private static TransactionCategorizer.Input in(String description, String amount) {
        return new TransactionCategorizer.Input(description, new BigDecimal(amount));
    }

    @Test
    void usesClaudesCodes_andStoresCardPaymentsAsCreditCardPayments() {
        when(claudeService.chat(anyString(), anyString()))
                .thenReturn("Here you go:\n[\"FOOD_AND_DRINK\", \"credit_card_payment\", \"INCOME\"]");

        List<TransactionCategorizer.Category> result = categorizer.categorize(List.of(
                in("STARBUCKS #123", "4.50"), in("ONLINE PAYMENT", "500.00"), in("ACME PAYROLL", "-2500.00")));

        assertThat(result).extracting(TransactionCategorizer.Category::category)
                .containsExactly("FOOD_AND_DRINK", "LOAN_PAYMENTS", "INCOME");
        assertThat(result.get(1).subcategory()).isEqualTo("LOAN_PAYMENTS_CREDIT_CARD_PAYMENT");
        assertThat(result.get(0).subcategory()).isNull();
    }

    @Test
    void unknownCodeFromClaude_fallsBackToKeywordsForThatRow() {
        when(claudeService.chat(anyString(), anyString())).thenReturn("[\"FOOD_AND_DRINK\", \"SNACKS\"]");

        List<TransactionCategorizer.Category> result = categorizer.categorize(List.of(
                in("CHIPOTLE", "12.00"), in("UBER TRIP", "18.00")));

        assertThat(result).extracting(TransactionCategorizer.Category::category)
                .containsExactly("FOOD_AND_DRINK", "TRANSPORTATION");
    }

    @Test
    void whenClaudeFailsOrMiscounts_usesKeywordRules() {
        when(claudeService.chat(anyString(), anyString()))
                .thenThrow(new AiServiceUnavailableException("down"))
                .thenReturn("[\"TRAVEL\"]");

        List<TransactionCategorizer.Input> inputs = List.of(
                in("PAYMENT THANK YOU", "300.00"), in("ZELLE FROM MOM", "-50.00"), in("RANDOM SHOP", "20.00"));
        List<TransactionCategorizer.Category> outage = categorizer.categorize(inputs);
        List<TransactionCategorizer.Category> miscounted = categorizer.categorize(inputs);

        assertThat(outage).extracting(TransactionCategorizer.Category::category)
                .containsExactly("LOAN_PAYMENTS", "TRANSFER_IN", "GENERAL_MERCHANDISE");
        assertThat(miscounted).isEqualTo(outage);
    }

    @Test
    void keywordRules_coverCommonCasesWithoutFalseMatches() {
        assertThat(TransactionCategorizer.byKeywords(in("DIRECT DEP ACME CORP", "-3000"))).isEqualTo("INCOME");
        assertThat(TransactionCategorizer.byKeywords(in("TRANSFER TO SAVINGS", "200"))).isEqualTo("TRANSFER_OUT");
        assertThat(TransactionCategorizer.byKeywords(in("NETFLIX.COM", "15.49"))).isEqualTo("ENTERTAINMENT");
        // "COFFEE" must not match the FEE rule
        assertThat(TransactionCategorizer.byKeywords(in("BLUE BOTTLE COFFEE", "6"))).isEqualTo("FOOD_AND_DRINK");
        assertThat(TransactionCategorizer.byKeywords(in("REFUND FROM STORE", "-25"))).isEqualTo("INCOME");
    }

    @Test
    void largeImports_areCategorizedInBatches() {
        List<TransactionCategorizer.Input> inputs = new ArrayList<>();
        for (int i = 0; i < 151; i++) {
            inputs.add(in("SHOP " + i, "1.00"));
        }
        when(claudeService.chat(anyString(), anyString())).thenThrow(new AiServiceUnavailableException("down"));

        assertThat(categorizer.categorize(inputs)).hasSize(151);
        verify(claudeService, times(2)).chat(anyString(), anyString());
    }
}

package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AICoachServiceTest {

    @Mock private ClaudeService claudeService;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AnalyticsService analyticsService;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private ConversationService conversationService;
    @Mock private MetricsService metricsService;

    @InjectMocks
    private AICoachService aiCoachService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        // metricsService uses @Autowired field injection in AICoachService (not constructor),
        // so @InjectMocks won't inject it — set it manually.
        ReflectionTestUtils.setField(aiCoachService, "metricsService", metricsService);
    }

    // --- chat ---

    @Test
    void chat_returnsClaudeResponse() {
        when(vectorStoreService.retrieveRelevantContext(eq(userId), anyString()))
                .thenReturn(List.of("tx: Starbucks $5.00"));
        when(conversationService.getHistory(eq(userId), eq("default")))
                .thenReturn(List.of());
        when(claudeService.chat(anyString(), anyString(), anyList()))
                .thenReturn("Here is your advice.");

        String result = aiCoachService.chat(userId, "How am I spending?");

        assertThat(result).isEqualTo("Here is your advice.");
    }

    @Test
    void chat_usesDefaultSessionIdForSingleArgOverload() {
        when(vectorStoreService.retrieveRelevantContext(any(), any())).thenReturn(List.of());
        when(conversationService.getHistory(any(), any())).thenReturn(List.of());
        when(claudeService.chat(anyString(), anyString(), anyList())).thenReturn("ok");

        aiCoachService.chat(userId, "Hi");

        verify(conversationService).getHistory(userId, "default");
        verify(conversationService).saveMessage(eq(userId), eq("default"), anyString(), anyString());
    }

    @Test
    void chat_includesRelevantContextInSystemPrompt() {
        when(vectorStoreService.retrieveRelevantContext(eq(userId), anyString()))
                .thenReturn(List.of("Starbucks $5.00", "Uber Eats $25.00"));
        when(conversationService.getHistory(any(), any())).thenReturn(List.of());
        when(claudeService.chat(anyString(), anyString(), anyList())).thenReturn("advice");

        aiCoachService.chat(userId, "What did I spend on food?", "session-1");

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeService).chat(anyString(), systemPromptCaptor.capture(), anyList());
        assertThat(systemPromptCaptor.getValue())
                .contains("Starbucks $5.00")
                .contains("Uber Eats $25.00");
    }

    @Test
    void chat_usesFallbackPromptWhenNoContextAvailable() {
        when(vectorStoreService.retrieveRelevantContext(any(), any())).thenReturn(List.of());
        when(conversationService.getHistory(any(), any())).thenReturn(List.of());
        when(claudeService.chat(anyString(), anyString(), anyList())).thenReturn("advice");

        aiCoachService.chat(userId, "Help me", "s1");

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeService).chat(anyString(), systemPromptCaptor.capture(), anyList());
        assertThat(systemPromptCaptor.getValue())
                .contains("No transaction data available");
    }

    @Test
    void chat_savesConversationTurnAfterResponse() {
        when(vectorStoreService.retrieveRelevantContext(any(), any())).thenReturn(List.of());
        when(conversationService.getHistory(any(), any())).thenReturn(List.of());
        when(claudeService.chat(anyString(), anyString(), anyList())).thenReturn("Claude reply");

        aiCoachService.chat(userId, "My question", "session-abc");

        verify(conversationService).saveMessage(userId, "session-abc", "My question", "Claude reply");
    }

    @Test
    void chat_passesConversationHistoryToClaude() {
        List<ClaudeService.ConversationTurn> history = List.of(
                new ClaudeService.ConversationTurn("prev question", "prev answer")
        );
        when(vectorStoreService.retrieveRelevantContext(any(), any())).thenReturn(List.of());
        when(conversationService.getHistory(userId, "s1")).thenReturn(history);
        when(claudeService.chat(anyString(), anyString(), anyList())).thenReturn("response");

        aiCoachService.chat(userId, "New question", "s1");

        verify(claudeService).chat(anyString(), anyString(), eq(history));
    }

    @Test
    void chat_recordsMetrics() {
        when(vectorStoreService.retrieveRelevantContext(any(), any())).thenReturn(List.of());
        when(conversationService.getHistory(any(), any())).thenReturn(List.of());
        when(claudeService.chat(anyString(), anyString(), anyList())).thenReturn("ok");

        aiCoachService.chat(userId, "Hello", "s1");

        verify(metricsService).recordAiCoachRequest();
        verify(metricsService).recordAiCoachResponseDuration(anyLong());
    }

    // --- clearSession ---

    @Test
    void clearSession_delegatesToConversationService() {
        aiCoachService.clearSession(userId, "my-session");
        verify(conversationService).clearSession(userId, "my-session");
    }

    // --- generateWeeklySummary ---

    @Test
    void generateWeeklySummary_returnsClaudeResponse() {
        when(analyticsService.getTotalSpending(eq(userId), any(), any()))
                .thenReturn(new BigDecimal("250.00"));
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("FOOD_AND_DRINK", new BigDecimal("100.00")));
        when(analyticsService.getTopMerchants(eq(userId), any(), any(), anyInt()))
                .thenReturn(Map.of("Starbucks", new BigDecimal("50.00")));
        when(claudeService.chat(anyString())).thenReturn("Great week!");

        String result = aiCoachService.generateWeeklySummary(userId);

        assertThat(result).isEqualTo("Great week!");
    }

    @Test
    void generateWeeklySummary_includesSpendingDataInPrompt() {
        when(analyticsService.getTotalSpending(eq(userId), any(), any()))
                .thenReturn(new BigDecimal("300.00"));
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("TRANSPORTATION", new BigDecimal("75.00")));
        when(analyticsService.getTopMerchants(eq(userId), any(), any(), anyInt()))
                .thenReturn(Map.of());
        when(claudeService.chat(anyString())).thenReturn("Summary");

        aiCoachService.generateWeeklySummary(userId);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeService).chat(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("300.00")
                .contains("TRANSPORTATION");
    }

    // --- generateMonthlyReport ---

    @Test
    void generateMonthlyReport_returnsClaudeResponse() {
        Map<String, Object> summary = Map.of(
                "month", LocalDate.now().toString(),
                "totalSpending", new BigDecimal("1500.00"),
                "totalIncome", new BigDecimal("3000.00"),
                "netCashFlow", new BigDecimal("1500.00"),
                "transactionCount", 42,
                "categoryBreakdown", Map.<String, BigDecimal>of(),
                "topMerchants", Map.<String, BigDecimal>of()
        );
        when(analyticsService.getMonthlySummary(eq(userId), any())).thenReturn(summary);
        when(claudeService.chat(anyString())).thenReturn("Monthly report done.");

        String result = aiCoachService.generateMonthlyReport(userId);

        assertThat(result).isEqualTo("Monthly report done.");
    }

    // --- analyzeCategorySpending ---

    @Test
    void analyzeCategorySpending_returnsClaudeResponse() {
        Transaction t = transaction("FOOD_AND_DRINK", 50.00, LocalDate.now().minusDays(5));
        when(transactionRepository.findByUserIdAndCategory(userId, "FOOD_AND_DRINK"))
                .thenReturn(List.of(t));
        when(claudeService.chat(anyString())).thenReturn("Food analysis.");

        String result = aiCoachService.analyzeCategorySpending(userId, "FOOD_AND_DRINK");

        assertThat(result).isEqualTo("Food analysis.");
    }

    @Test
    void analyzeCategorySpending_excludesTransactionsOlderThan30Days() {
        Transaction recent = transaction("FOOD_AND_DRINK", 50.00, LocalDate.now().minusDays(10));
        Transaction old = transaction("FOOD_AND_DRINK", 200.00, LocalDate.now().minusDays(45));
        when(transactionRepository.findByUserIdAndCategory(userId, "FOOD_AND_DRINK"))
                .thenReturn(List.of(recent, old));
        when(claudeService.chat(anyString())).thenReturn("ok");

        aiCoachService.analyzeCategorySpending(userId, "FOOD_AND_DRINK");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeService).chat(promptCaptor.capture());
        // Only the recent $50 transaction should be counted — not the old $200 one
        assertThat(promptCaptor.getValue())
                .contains("50.00")
                .doesNotContain("250.00");
    }

    @Test
    void analyzeCategorySpending_includesTransactionCountInPrompt() {
        Transaction t1 = transaction("FOOD_AND_DRINK", 20.00, LocalDate.now().minusDays(3));
        Transaction t2 = transaction("FOOD_AND_DRINK", 30.00, LocalDate.now().minusDays(7));
        when(transactionRepository.findByUserIdAndCategory(userId, "FOOD_AND_DRINK"))
                .thenReturn(List.of(t1, t2));
        when(claudeService.chat(anyString())).thenReturn("ok");

        aiCoachService.analyzeCategorySpending(userId, "FOOD_AND_DRINK");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeService).chat(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("2");
    }

    // --- getSavingsRecommendations ---

    @Test
    void getSavingsRecommendations_returnsClaudeResponse() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of("FOOD_AND_DRINK", new BigDecimal("400.00")));
        when(analyticsService.getTotalSpending(eq(userId), any(), any()))
                .thenReturn(new BigDecimal("2000.00"));
        when(analyticsService.getTotalIncome(eq(userId), any(), any()))
                .thenReturn(new BigDecimal("4000.00"));
        when(claudeService.chat(anyString())).thenReturn("Save more!");

        String result = aiCoachService.getSavingsRecommendations(userId, new BigDecimal("500.00"));

        assertThat(result).isEqualTo("Save more!");
    }

    @Test
    void getSavingsRecommendations_includesSavingsGoalInPrompt() {
        when(analyticsService.getSpendingByCategory(eq(userId), any(), any()))
                .thenReturn(Map.of());
        when(analyticsService.getTotalSpending(eq(userId), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(analyticsService.getTotalIncome(eq(userId), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(claudeService.chat(anyString())).thenReturn("ok");

        aiCoachService.getSavingsRecommendations(userId, new BigDecimal("750.00"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(claudeService).chat(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("750.00");
    }

    // --- helper ---

    private Transaction transaction(String category, double amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setCategory(category);
        t.setMerchantName(category + " Merchant");
        t.setName(category + " tx");
        t.setAmount(BigDecimal.valueOf(amount));
        t.setDate(date);
        t.setPlaidTransactionId(UUID.randomUUID().toString());
        t.setAccountId(UUID.randomUUID());
        return t;
    }
}

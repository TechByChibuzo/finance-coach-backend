package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AICoachService {

    private final ClaudeService claudeService;
    private final TransactionRepository transactionRepository;
    private final AnalyticsService analyticsService;
    private final VectorStoreService vectorStoreService;
    private final ConversationService conversationService;


    @Autowired
    private MetricsService metricsService;

    @Autowired
    public AICoachService(ClaudeService claudeService,
                          TransactionRepository transactionRepository,
                          AnalyticsService analyticsService,
                          VectorStoreService vectorStoreService,
                          ConversationService conversationService) {
        this.claudeService = claudeService;
        this.transactionRepository = transactionRepository;
        this.analyticsService = analyticsService;
        this.vectorStoreService = vectorStoreService;
        this.conversationService = conversationService;
    }

    public String chat(UUID userId, String userMessage) {
        return chat(userId, userMessage, "default");
    }

    public String chat(UUID userId, String userMessage, String sessionId) {
        long startTime = System.currentTimeMillis();

        metricsService.recordAiCoachRequest();

        // RAG — retrieve relevant transactions
        List<String> relevantContext = vectorStoreService.retrieveRelevantContext(userId, userMessage);

        // Build system prompt with RAG context
        String systemPrompt = buildSystemPrompt(relevantContext);

        // Load conversation history for this session
        List<ClaudeService.ConversationTurn> history = conversationService.getHistory(userId, sessionId);

        // Call Claude with history
        String response = claudeService.chat(userMessage, systemPrompt, history);

        // Save this turn to memory
        conversationService.saveMessage(userId, sessionId, userMessage, response);

        long duration = System.currentTimeMillis() - startTime;
        metricsService.recordAiCoachResponseDuration(duration);

        return response;
    }

    public void clearSession(UUID userId, String sessionId) {
        conversationService.clearSession(userId, sessionId);
    }

    private String buildSystemPrompt(List<String> relevantTransactions) {
        String contextBlock = relevantTransactions.isEmpty()
                ? "No transaction data available yet. Encourage the user to connect their bank account."
                : String.join("\n", relevantTransactions);

        return """
        You are a friendly and knowledgeable personal finance coach.
        You help users understand their spending, save money, and make better financial decisions.
        
        Be conversational, supportive, and specific. Use the user's actual transaction data below
        to give personalized advice. Don't make up numbers — only reference what's in the context.
        
        Keep responses concise (2-3 paragraphs max) unless the user asks for detailed analysis.
        
        RELEVANT TRANSACTION CONTEXT (retrieved based on the user's question):
        """ + contextBlock;
    }

    /**
     * Generate weekly spending summary
     */
    public String generateWeeklySummary(UUID userId) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);

        // Get spending data (all BigDecimal now)
        BigDecimal totalSpending = analyticsService.getTotalSpending(userId, startDate, endDate);
        Map<String, BigDecimal> categoryBreakdown = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, BigDecimal> topMerchants = analyticsService.getTopMerchants(userId, startDate, endDate, 5);

        // Build summary prompt
        String prompt = String.format("""
            Generate a friendly weekly spending summary for the user based on this data:
            
            Week: %s to %s
            Total Spending: $%.2f
            
            Spending by Category:
            %s
            
            Top Merchants:
            %s
            
            Please provide:
            1. A brief overview of their spending this week
            2. One notable observation or trend
            3. One actionable tip to save money or improve their finances
            
            Keep it conversational and supportive. Use "you" and "your" to address the user directly.
            """,
                startDate, endDate, totalSpending,
                formatCategoryBreakdown(categoryBreakdown),
                formatTopMerchants(topMerchants)
        );

        return claudeService.chat(prompt);
    }

    /**
     * Generate monthly report
     */
    public String generateMonthlyReport(UUID userId) {
        LocalDate now = LocalDate.now();

        Map<String, Object> monthlySummary = analyticsService.getMonthlySummary(userId, now);

        // Cast to BigDecimal (not Double)
        BigDecimal totalSpending = (BigDecimal) monthlySummary.get("totalSpending");
        BigDecimal totalIncome = (BigDecimal) monthlySummary.get("totalIncome");
        BigDecimal netCashFlow = (BigDecimal) monthlySummary.get("netCashFlow");
        Integer transactionCount = (Integer) monthlySummary.get("transactionCount");

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> categoryBreakdown = (Map<String, BigDecimal>) monthlySummary.get("categoryBreakdown");

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> topMerchants = (Map<String, BigDecimal>) monthlySummary.get("topMerchants");

        String prompt = String.format("""
            Generate a comprehensive monthly financial report based on this data:
            
            Month: %s
            Total Spending: $%.2f
            Total Income: $%.2f
            Net Cash Flow: $%.2f
            Total Transactions: %d
            
            Category Breakdown:
            %s
            
            Top Merchants:
            %s
            
            Please provide:
            1. Overview of the month
            2. Key insights and patterns
            3. Areas where they're doing well
            4. Opportunities for improvement
            5. Specific actionable recommendations
            
            Make it encouraging and actionable.
            """,
                monthlySummary.get("month"),
                totalSpending,
                totalIncome,
                netCashFlow,
                transactionCount,
                formatCategoryBreakdown(categoryBreakdown),
                formatTopMerchants(topMerchants)
        );

        return claudeService.chat(prompt);
    }

    /**
     * Analyze spending in a specific category
     */
    public String analyzeCategorySpending(UUID userId, String category) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        List<Transaction> categoryTransactions = transactionRepository
                .findByUserIdAndCategory(userId, category);

        // Use BigDecimal for money calculations
        BigDecimal totalCategorySpending = categoryTransactions.stream()
                .filter(t -> t.getDate().isAfter(startDate) && t.getDate().isBefore(endDate))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Get merchant breakdown with BigDecimal
        Map<String, BigDecimal> merchantBreakdown = categoryTransactions.stream()
                .filter(t -> t.getDate().isAfter(startDate) && t.getDate().isBefore(endDate))
                .collect(Collectors.groupingBy(
                        t -> t.getMerchantName() != null ? t.getMerchantName() : "Unknown",
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                Transaction::getAmount,
                                BigDecimal::add
                        )
                ));

        String prompt = String.format("""
            Analyze the user's spending in the %s category:
            
            Last 30 days spending: $%.2f
            Number of transactions: %d
            
            Breakdown by merchant:
            %s
            
            Please provide:
            1. Analysis of their spending pattern in this category
            2. Is this spending reasonable or excessive?
            3. Specific tips to reduce spending in this category
            4. Alternatives or strategies they could try
            
            Be specific and actionable.
            """,
                category,
                totalCategorySpending,
                categoryTransactions.size(),
                formatMerchantBreakdown(merchantBreakdown)
        );

        return claudeService.chat(prompt);
    }

    /**
     * Get savings recommendations
     */
    public String getSavingsRecommendations(UUID userId, BigDecimal savingsGoal) {  // ✅ Changed from Double
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        Map<String, BigDecimal> categorySpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        BigDecimal totalSpending = analyticsService.getTotalSpending(userId, startDate, endDate);
        BigDecimal totalIncome = analyticsService.getTotalIncome(userId, startDate, endDate);

        // Calculate current savings
        BigDecimal currentSavings = totalIncome.subtract(totalSpending);

        // Calculate savings rate percentage (can be Double for display)
        Double savingsRatePercentage = totalIncome.compareTo(BigDecimal.ZERO) > 0
                ? currentSavings.divide(totalIncome, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .doubleValue()
                : 0.0;

        String prompt = String.format("""
            The user wants to save $%.2f per month. Help them create a plan.
            
            Current monthly spending: $%.2f
            Current monthly income: $%.2f
            Current savings: $%.2f (%.1f%%)
            
            Spending by category:
            %s
            
            Please provide:
            1. Assessment of whether their savings goal is achievable
            2. Specific categories where they can cut back
            3. Concrete dollar amounts they should reduce in each category
            4. Practical tips that won't drastically change their lifestyle
            5. A clear action plan
            
            Be realistic and encouraging.
            """,
                savingsGoal,
                totalSpending,
                totalIncome,
                currentSavings,
                savingsRatePercentage,
                formatCategoryBreakdown(categorySpending)
        );

        return claudeService.chat(prompt);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Build financial context for the user
     */
    private String buildUserFinancialContext(UUID userId) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        BigDecimal totalSpending = analyticsService.getTotalSpending(userId, startDate, endDate);
        Map<String, BigDecimal> categoryBreakdown = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, BigDecimal> topMerchants = analyticsService.getTopMerchants(userId, startDate, endDate, 5);

        return String.format("""
            User's Financial Context (Last 30 days):
            Total Spending: $%.2f
            
            Spending by Category:
            %s
            
            Top Merchants:
            %s
            """,
                totalSpending,
                formatCategoryBreakdown(categoryBreakdown),
                formatTopMerchants(topMerchants)
        );
    }

    /**
     * Format category breakdown for Claude prompt
     */
    private String formatCategoryBreakdown(Map<String, BigDecimal> categories) {
        return categories.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())  // ✅ Fixed generic type
                .map(e -> String.format("- %s: $%.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Format top merchants for Claude prompt
     */
    private String formatTopMerchants(Map<String, BigDecimal> merchants) {
        return merchants.entrySet().stream()
                .map(e -> String.format("- %s: $%.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Format merchant breakdown for Claude prompt
     */
    private String formatMerchantBreakdown(Map<String, BigDecimal> merchants) {
        return merchants.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> String.format("- %s: $%.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }
}
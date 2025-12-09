// src/main/java/com/financecoach/userservice/service/AICoachService.java
package com.financecoach.userservice.service;

import com.financecoach.userservice.model.Transaction;
import com.financecoach.userservice.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    @Autowired
    public AICoachService(ClaudeService claudeService,
                          TransactionRepository transactionRepository,
                          AnalyticsService analyticsService) {
        this.claudeService = claudeService;
        this.transactionRepository = transactionRepository;
        this.analyticsService = analyticsService;
    }

    /**
     * General financial chat with context
     */
    public String chat(UUID userId, String userMessage) {
        // Build context about user's finances
        String context = buildUserFinancialContext(userId);

        // System prompt for Claude
        String systemPrompt = """
            You are a friendly and knowledgeable personal finance coach. 
            You help users understand their spending, save money, and make better financial decisions.
            
            Be conversational, supportive, and specific. Use the user's actual data to give personalized advice.
            Keep responses concise (2-3 paragraphs max) unless the user asks for detailed analysis.
            """;

        // Combine context with user message
        String fullMessage = context + "\n\nUser asks: " + userMessage;

        return claudeService.chat(fullMessage, systemPrompt);
    }

    /**
     * Generate weekly spending summary
     */
    public String generateWeeklySummary(UUID userId) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);

        // Get spending data
        Double totalSpending = analyticsService.getTotalSpending(userId, startDate, endDate);
        Map<String, Double> categoryBreakdown = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, Double> topMerchants = analyticsService.getTopMerchants(userId, startDate, endDate, 5);

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
        LocalDate startDate = now.withDayOfMonth(1);
        LocalDate endDate = now;

        Map<String, Object> monthlySummary = analyticsService.getMonthlySummary(userId, now);

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
                monthlySummary.get("totalSpending"),
                monthlySummary.get("totalIncome"),
                monthlySummary.get("netCashFlow"),
                monthlySummary.get("transactionCount"),
                formatCategoryBreakdown((Map<String, Double>) monthlySummary.get("categoryBreakdown")),
                formatTopMerchants((Map<String, Double>) monthlySummary.get("topMerchants"))
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

        Double totalCategorySpending = categoryTransactions.stream()
                .filter(t -> t.getDate().isAfter(startDate) && t.getDate().isBefore(endDate))
                .mapToDouble(Transaction::getAmount)
                .sum();

        // Get merchant breakdown
        Map<String, Double> merchantBreakdown = categoryTransactions.stream()
                .filter(t -> t.getDate().isAfter(startDate) && t.getDate().isBefore(endDate))
                .collect(Collectors.groupingBy(
                        t -> t.getMerchantName() != null ? t.getMerchantName() : "Unknown",
                        Collectors.summingDouble(Transaction::getAmount)
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
    public String getSavingsRecommendations(UUID userId, Double savingsGoal) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        Map<String, Double> categorySpending = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Double totalSpending = analyticsService.getTotalSpending(userId, startDate, endDate);
        Double totalIncome = analyticsService.getTotalIncome(userId, startDate, endDate);

        String prompt = String.format("""
            The user wants to save $%.2f per month. Help them create a plan.
            
            Current monthly spending: $%.2f
            Current monthly income: $%.2f
            Current savings rate: $%.2f (%.1f%%)
            
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
                totalIncome - totalSpending,
                ((totalIncome - totalSpending) / totalIncome) * 100,
                formatCategoryBreakdown(categorySpending)
        );

        return claudeService.chat(prompt);
    }

    // Helper methods

    private String buildUserFinancialContext(UUID userId) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        Double totalSpending = analyticsService.getTotalSpending(userId, startDate, endDate);
        Map<String, Double> categoryBreakdown = analyticsService.getSpendingByCategory(userId, startDate, endDate);
        Map<String, Double> topMerchants = analyticsService.getTopMerchants(userId, startDate, endDate, 5);

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

    private String formatCategoryBreakdown(Map<String, Double> categories) {
        return categories.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> String.format("- %s: $%.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    private String formatTopMerchants(Map<String, Double> merchants) {
        return merchants.entrySet().stream()
                .map(e -> String.format("- %s: $%.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    private String formatMerchantBreakdown(Map<String, Double> merchants) {
        return merchants.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> String.format("- %s: $%.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }
}
// src/main/java/com/financecoach/backend/controller/AICoachController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.ChatRequest;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.service.AICoachService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai-coach")
public class AICoachController {

    private final AICoachService aiCoachService;

    @Autowired
    public AICoachController(AICoachService aiCoachService) {
        this.aiCoachService = aiCoachService;
    }

    /**
     * Chat with AI coach
     * POST /api/ai-coach/chat
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@Valid @RequestBody ChatRequest request) {
        UUID userId = getCurrentUserId();
        String response = aiCoachService.chat(userId, request.getMessage());
        return ResponseEntity.ok(Map.of("response", response));
    }

    /**
     * Get weekly summary
     * GET /api/ai-coach/weekly-summary
     */
    @GetMapping("/weekly-summary")
    public ResponseEntity<Map<String, String>> getWeeklySummary() {
        UUID userId = getCurrentUserId();
        String summary = aiCoachService.generateWeeklySummary(userId);
        return ResponseEntity.ok(Map.of("summary", summary));
    }

    /**
     * Get monthly report
     * GET /api/ai-coach/monthly-report
     */
    @GetMapping("/monthly-report")
    public ResponseEntity<Map<String, String>> getMonthlyReport() {
        UUID userId = getCurrentUserId();
        String report = aiCoachService.generateMonthlyReport(userId);
        return ResponseEntity.ok(Map.of("report", report));
    }

    /**
     * Analyze category spending
     * GET /api/ai-coach/analyze-category/{category}
     */
    @GetMapping("/analyze-category/{category}")
    public ResponseEntity<Map<String, String>> analyzeCategory(@PathVariable String category) {
        UUID userId = getCurrentUserId();
        String analysis = aiCoachService.analyzeCategorySpending(userId, category);
        return ResponseEntity.ok(Map.of("analysis", analysis));
    }

    /**
     * Get savings recommendations
     * POST /api/ai-coach/savings-recommendations
     */
    @PostMapping("/savings-recommendations")
    public ResponseEntity<Map<String, String>> getSavingsRecommendations(
            @RequestBody Map<String, Double> request) {

        UUID userId = getCurrentUserId();
        Double savingsGoal = request.get("savingsGoal");

        if (savingsGoal == null || savingsGoal <= 0) {
            throw new ValidationException("Savings goal must be positive");
        }

        String recommendations = aiCoachService.getSavingsRecommendations(userId, savingsGoal);
        return ResponseEntity.ok(Map.of("recommendations", recommendations));
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }
}
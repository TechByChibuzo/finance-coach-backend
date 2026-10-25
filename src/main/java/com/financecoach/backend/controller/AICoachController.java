// src/main/java/com/financecoach/backend/controller/AICoachController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.annotation.RequiresFeature;
import com.financecoach.backend.annotation.TrackUsage;
import com.financecoach.backend.dto.ChatRequest;
import com.financecoach.backend.dto.ChatTurnResponse;
import com.financecoach.backend.dto.SavingsGoalRequest;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.security.CurrentUser;
import com.financecoach.backend.service.AICoachService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
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
    @RequiresFeature(value = "ai_coach", message = "AI coach is not available on your plan")
    @TrackUsage(feature = "ai_coach")
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@Valid @RequestBody ChatRequest request) {
        UUID userId = CurrentUser.id();
        String response = aiCoachService.chat(userId, request.getMessage(), request.getSessionId());
        return ResponseEntity.ok(Map.of("response", response));
    }

    /**
     * Earlier messages of a chat session (not counted as AI usage)
     * GET /api/ai-coach/chat/{sessionId}
     */
    @GetMapping("/chat/{sessionId}")
    public ResponseEntity<List<ChatTurnResponse>> getConversation(@PathVariable String sessionId) {
        return ResponseEntity.ok(aiCoachService.getConversation(CurrentUser.id(), sessionId));
    }

    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        UUID userId = CurrentUser.id();
        aiCoachService.clearSession(userId, sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get weekly summary
     * GET /api/ai-coach/weekly-summary
     */
    @RequiresFeature(value = "ai_coach", message = "AI coach is not available on your plan")
    @TrackUsage(feature = "ai_coach")
    @GetMapping("/weekly-summary")
    public ResponseEntity<Map<String, String>> getWeeklySummary() {
        UUID userId = CurrentUser.id();
        String summary = aiCoachService.generateWeeklySummary(userId);
        return ResponseEntity.ok(Map.of("summary", summary));
    }

    /**
     * Get monthly report
     * GET /api/ai-coach/monthly-report
     */
    @RequiresFeature(value = "ai_coach", message = "AI coach is not available on your plan")
    @TrackUsage(feature = "ai_coach")
    @GetMapping("/monthly-report")
    public ResponseEntity<Map<String, String>> getMonthlyReport() {
        UUID userId = CurrentUser.id();
        String report = aiCoachService.generateMonthlyReport(userId);
        return ResponseEntity.ok(Map.of("report", report));
    }

    /**
     * Analyze category spending
     * GET /api/ai-coach/analyze-category/{category}
     */
    @RequiresFeature(value = "ai_coach", message = "AI coach is not available on your plan")
    @TrackUsage(feature = "ai_coach")
    @GetMapping("/analyze-category/{category}")
    public ResponseEntity<Map<String, String>> analyzeCategory(@PathVariable String category) {
        UUID userId = CurrentUser.id();
        String analysis = aiCoachService.analyzeCategorySpending(userId, category);
        return ResponseEntity.ok(Map.of("analysis", analysis));
    }

    /**
     * Get savings recommendations
     * POST /api/ai-coach/savings-recommendations
     * ✅ UPDATED: Now uses BigDecimal and proper DTO
     */
    @RequiresFeature(value = "ai_coach", message = "AI coach is not available on your plan")
    @TrackUsage(feature = "ai_coach")
    @PostMapping("/savings-recommendations")
    public ResponseEntity<Map<String, String>> getSavingsRecommendations(
            @Valid @RequestBody SavingsGoalRequest request) {

        UUID userId = CurrentUser.id();

        String recommendations = aiCoachService.getSavingsRecommendations(
                userId,
                request.getSavingsGoal()
        );

        return ResponseEntity.ok(Map.of("recommendations", recommendations));
    }

}
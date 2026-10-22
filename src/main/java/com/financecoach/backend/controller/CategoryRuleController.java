package com.financecoach.backend.controller;

import com.financecoach.backend.model.CategoryRule;
import com.financecoach.backend.service.CategoryRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The user's "always categorize this merchant as..." rules
 */
@RestController
@RequestMapping("/api/category-rules")
public class CategoryRuleController {

    private final CategoryRuleService categoryRuleService;

    public CategoryRuleController(CategoryRuleService categoryRuleService) {
        this.categoryRuleService = categoryRuleService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getRules() {
        return ResponseEntity.ok(categoryRuleService.getRules(currentUserId()).stream()
                .map(CategoryRuleController::toResponse)
                .toList());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        categoryRuleService.deleteRule(currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> toResponse(CategoryRule rule) {
        return Map.of(
                "id", rule.getId(),
                "merchant", rule.getDisplayName() == null ? rule.getMatchKey() : rule.getDisplayName(),
                "category", rule.getCategory());
    }

    private static UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}

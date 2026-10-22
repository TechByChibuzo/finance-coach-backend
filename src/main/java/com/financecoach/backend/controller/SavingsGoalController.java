package com.financecoach.backend.controller;

import com.financecoach.backend.dto.GoalRequest;
import com.financecoach.backend.dto.SavingsGoalResponse;
import com.financecoach.backend.service.SavingsGoalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The user's savings goals
 */
@RestController
@RequestMapping("/api/goals")
public class SavingsGoalController {

    private final SavingsGoalService goalService;

    public SavingsGoalController(SavingsGoalService goalService) {
        this.goalService = goalService;
    }

    @GetMapping
    public ResponseEntity<List<SavingsGoalResponse>> getGoals() {
        return ResponseEntity.ok(goalService.getGoals(currentUserId(), LocalDate.now()));
    }

    @PostMapping
    public ResponseEntity<SavingsGoalResponse> create(@Valid @RequestBody GoalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(goalService.create(currentUserId(), request, LocalDate.now()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SavingsGoalResponse> update(@PathVariable UUID id, @Valid @RequestBody GoalRequest request) {
        return ResponseEntity.ok(goalService.update(currentUserId(), id, request, LocalDate.now()));
    }

    /**
     * Add money to a goal tracked by hand (a negative amount takes some out)
     * POST /api/goals/{id}/saved
     */
    @PostMapping("/{id}/saved")
    public ResponseEntity<SavingsGoalResponse> addToSaved(@PathVariable UUID id, @Valid @RequestBody AmountRequest request) {
        return ResponseEntity.ok(goalService.addToSaved(currentUserId(), id, request.amount(), LocalDate.now()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        goalService.delete(currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    public record AmountRequest(@NotNull BigDecimal amount) {
    }

    private static UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}

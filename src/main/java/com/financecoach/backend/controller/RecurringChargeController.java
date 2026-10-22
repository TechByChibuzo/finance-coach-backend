package com.financecoach.backend.controller;

import com.financecoach.backend.dto.RecurringChargeResponse;
import com.financecoach.backend.service.RecurringChargeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Bills and subscriptions found in the user's transactions
 */
@RestController
@RequestMapping("/api/recurring")
public class RecurringChargeController {

    private final RecurringChargeService recurringChargeService;

    public RecurringChargeController(RecurringChargeService recurringChargeService) {
        this.recurringChargeService = recurringChargeService;
    }

    @GetMapping
    public ResponseEntity<List<RecurringChargeResponse>> getRecurring() {
        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(recurringChargeService.findRecurring(userId));
    }
}

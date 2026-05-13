package com.financecoach.backend.controller;

import com.financecoach.backend.service.ExportService;
import com.financecoach.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    @Autowired
    private ExportService exportService;

    @Autowired
    private UserService userService;

    @GetMapping("/transactions")
    public ResponseEntity<?> exportTransactions(Authentication authentication) {
        UUID userId = getCurrentUserId();
        String downloadUrl = exportService.exportTransactionsCSV(userId);
        return ResponseEntity.ok(Map.of(
                "downloadUrl", downloadUrl,
                "expiresIn", "15 minutes"
        ));
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }
}
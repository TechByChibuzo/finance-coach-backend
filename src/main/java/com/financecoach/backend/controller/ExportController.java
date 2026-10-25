package com.financecoach.backend.controller;

import com.financecoach.backend.annotation.RequiresFeature;
import com.financecoach.backend.security.CurrentUser;
import com.financecoach.backend.service.ExportService;
import com.financecoach.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

    @RequiresFeature(value = "export_reports", message = "Upgrade to Premium to export your transactions")
    @GetMapping("/transactions")
    public ResponseEntity<?> exportTransactions(Authentication authentication) {
        UUID userId = CurrentUser.id();
        String downloadUrl = exportService.exportTransactionsCSV(userId);
        return ResponseEntity.ok(Map.of(
                "downloadUrl", downloadUrl,
                "expiresIn", "15 minutes"
        ));
    }

}
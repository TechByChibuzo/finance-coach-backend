// src/main/java/com/financecoach/userservice/controller/PlaidController.java
package com.financecoach.userservice.controller;

import com.financecoach.userservice.dto.plaid.BankAccountResponse;
import com.financecoach.userservice.dto.plaid.ExchangeTokenRequest;
import com.financecoach.userservice.dto.plaid.LinkTokenResponse;
import com.financecoach.userservice.model.BankAccount;
import com.financecoach.userservice.service.PlaidService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/plaid")
public class PlaidController {

    private final PlaidService plaidService;

    @Autowired
    public PlaidController(PlaidService plaidService) {
        this.plaidService = plaidService;
    }

    /**
     * Create a link token to initialize Plaid Link
     */
    @PostMapping("/create-link-token")
    public ResponseEntity<?> createLinkToken() {
        try {
            UUID userId = getCurrentUserId();

            String linkToken = plaidService.createLinkToken(userId, "user");

            LinkTokenResponse response = new LinkTokenResponse(linkToken, null);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error creating link token: " + e.getMessage());
        }
    }

    /**
     * Exchange public token for access token and save accounts
     */
    @PostMapping("/exchange-token")
    public ResponseEntity<?> exchangeToken(@RequestBody ExchangeTokenRequest request) {
        try {
            UUID userId = getCurrentUserId();
            List<BankAccount> accounts = plaidService.exchangePublicToken(
                    userId,
                    request.getPublicToken()
            );

            List<BankAccountResponse> response = accounts.stream()
                    .map(this::convertToResponse)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error exchanging token: " + e.getMessage());
        }
    }

    /**
     * Get all connected bank accounts for current user
     */
    @GetMapping("/accounts")
    public ResponseEntity<List<BankAccountResponse>> getAccounts() {
        UUID userId = getCurrentUserId();

        List<BankAccount> accounts = plaidService.getUserBankAccounts(userId);

        List<BankAccountResponse> response = accounts.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Disconnect a bank account
     */
    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<Void> disconnectAccount(@PathVariable UUID accountId) {
        UUID userId = getCurrentUserId();

        plaidService.disconnectBankAccount(accountId, userId);

        return ResponseEntity.noContent().build();
    }

    // Helper methods
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }

    private BankAccountResponse convertToResponse(BankAccount account) {
        return new BankAccountResponse(
                account.getId(),
                account.getInstitutionName(),
                account.getAccountName(),
                account.getAccountType(),
                account.getCurrentBalance(),
                account.getAvailableBalance(),
                account.getCurrencyCode(),
                account.getLastSyncedAt(),
                account.getIsActive()
        );
    }
}
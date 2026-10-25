package com.financecoach.backend.controller;

import com.financecoach.backend.dto.plaid.BankAccountResponse;
import com.financecoach.backend.dto.plaid.ExchangeTokenRequest;
import com.financecoach.backend.dto.plaid.LinkTokenResponse;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.security.CurrentUser;
import com.financecoach.backend.service.PlaidService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
     * POST /api/plaid/create-link-token
     */
    @PostMapping("/create-link-token")
    public ResponseEntity<LinkTokenResponse> createLinkToken() {
        UUID userId = CurrentUser.id();
        String linkToken = plaidService.createLinkToken(userId, "user");
        LinkTokenResponse response = new LinkTokenResponse(linkToken, null);
        return ResponseEntity.ok(response);
    }

    /**
     * Exchange public token for access token and save accounts
     * POST /api/plaid/exchange-token
     */
    @PostMapping("/exchange-token")
    public ResponseEntity<List<BankAccountResponse>> exchangeToken(
            @Valid @RequestBody ExchangeTokenRequest request) {

        UUID userId = CurrentUser.id();
        List<BankAccount> accounts = plaidService.exchangePublicToken(
                userId,
                request.getPublicToken()
        );

        List<BankAccountResponse> response = accounts.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Get all connected bank accounts for current user
     * GET /api/plaid/accounts
     */
    @GetMapping("/accounts")
    public ResponseEntity<List<BankAccountResponse>> getAccounts() {
        UUID userId = CurrentUser.id();
        List<BankAccount> accounts = plaidService.getUserBankAccounts(userId);
        Map<UUID, PlaidItem> items = plaidService.getItemsWithBranding(accounts);

        List<BankAccountResponse> response = accounts.stream()
                .map(account -> convertToResponse(account, items.get(account.getItemId())))
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * Disconnect a bank account
     * DELETE /api/plaid/accounts/{accountId}
     */
    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<Void> disconnectAccount(@PathVariable UUID accountId) {
        UUID userId = CurrentUser.id();
        plaidService.disconnectBankAccount(accountId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * A link token that reopens Plaid Link on an existing bank connection, to sign in again
     * POST /api/plaid/items/{itemId}/update-link-token
     */
    @PostMapping("/items/{itemId}/update-link-token")
    public ResponseEntity<LinkTokenResponse> createUpdateLinkToken(@PathVariable UUID itemId) {
        String linkToken = plaidService.createUpdateLinkToken(CurrentUser.id(), itemId);
        return ResponseEntity.ok(new LinkTokenResponse(linkToken, null));
    }

    /**
     * The user finished signing in to the bank again
     * POST /api/plaid/items/{itemId}/reconnected
     */
    @PostMapping("/items/{itemId}/reconnected")
    public ResponseEntity<Void> markReconnected(@PathVariable UUID itemId) {
        plaidService.markReconnected(CurrentUser.id(), itemId);
        return ResponseEntity.noContent().build();
    }

    private BankAccountResponse convertToResponse(BankAccount account) {
        return convertToResponse(account, null);
    }

    private BankAccountResponse convertToResponse(BankAccount account, PlaidItem item) {
        BankAccountResponse response = new BankAccountResponse(
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
        response.setMask(account.getMask());
        response.setItemId(account.getItemId());
        if (item != null) {
            response.setNeedsReconnect(Boolean.TRUE.equals(item.getNeedsReconnect()));
            String logo = item.getInstitutionLogo();
            if (logo != null) {
                // Site icons are stored as data URLs already; Plaid's logos are bare base64 PNGs
                response.setInstitutionLogo(logo.startsWith("data:") ? logo : "data:image/png;base64," + logo);
            }
            response.setInstitutionColor(item.getInstitutionColor());
        }
        return response;
    }
}
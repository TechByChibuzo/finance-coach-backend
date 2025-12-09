// src/main/java/com/financecoach/userservice/service/PlaidService.java
package com.financecoach.userservice.service;

import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import com.financecoach.userservice.model.BankAccount;
import com.financecoach.userservice.repository.BankAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class PlaidService {

    private final PlaidApi plaidClient;
    private final BankAccountRepository bankAccountRepository;

    @Autowired
    public PlaidService(PlaidApi plaidClient, BankAccountRepository bankAccountRepository) {
        this.plaidClient = plaidClient;
        this.bankAccountRepository = bankAccountRepository;
    }

    /**
     * Create a link token for Plaid Link
     */
    public String createLinkToken(UUID userId, String username) throws IOException {
        // Create user object
        LinkTokenCreateRequestUser user = new LinkTokenCreateRequestUser()
                .clientUserId(userId.toString());

        // Create link token request
        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(user)
                .clientName("Finance Coach")
                .products(Arrays.asList(Products.TRANSACTIONS))
                .countryCodes(Arrays.asList(CountryCode.US))
                .language("en");

        // Execute request
        Response<LinkTokenCreateResponse> response = plaidClient
                .linkTokenCreate(request)
                .execute();

        if (response.isSuccessful() && response.body() != null) {
            return response.body().getLinkToken();
        } else {
            throw new RuntimeException("Failed to create link token: " + response.errorBody().string());
        }
    }

    /**
     * Exchange public token for access token and save account
     */
    public List<BankAccount> exchangePublicToken(UUID userId, String publicToken) throws IOException {
        // Exchange public token for access token
        ItemPublicTokenExchangeRequest exchangeRequest = new ItemPublicTokenExchangeRequest()
                .publicToken(publicToken);

        Response<ItemPublicTokenExchangeResponse> exchangeResponse = plaidClient
                .itemPublicTokenExchange(exchangeRequest)
                .execute();

        if (!exchangeResponse.isSuccessful() || exchangeResponse.body() == null) {
            throw new RuntimeException("Failed to exchange public token: " +
                    exchangeResponse.errorBody().string());
        }

        String accessToken = exchangeResponse.body().getAccessToken();
        String itemId = exchangeResponse.body().getItemId();

        // Get account details
        AccountsGetRequest accountsRequest = new AccountsGetRequest()
                .accessToken(accessToken);

        Response<AccountsGetResponse> accountsResponse = plaidClient
                .accountsGet(accountsRequest)
                .execute();

        if (!accountsResponse.isSuccessful() || accountsResponse.body() == null) {
            throw new RuntimeException("Failed to get accounts: " +
                    accountsResponse.errorBody().string());
        }

        // Get institution info
        String institutionId = accountsResponse.body().getItem().getInstitutionId();
        String institutionName = "Unknown";

        if (institutionId != null) {
            try {
                InstitutionsGetByIdRequest institutionRequest = new InstitutionsGetByIdRequest()
                        .institutionId(institutionId)
                        .countryCodes(Arrays.asList(CountryCode.US));

                Response<InstitutionsGetByIdResponse> institutionResponse = plaidClient
                        .institutionsGetById(institutionRequest)
                        .execute();

                if (institutionResponse.isSuccessful() && institutionResponse.body() != null) {
                    institutionName = institutionResponse.body().getInstitution().getName();
                }
            } catch (Exception e) {
                // If institution fetch fails, continue with "Unknown"
            }
        }

        // Save accounts to database
        List<BankAccount> savedAccounts = new ArrayList<>();
        for (AccountBase account : accountsResponse.body().getAccounts()) {
            // Check if account already exists
            if (bankAccountRepository.existsByPlaidAccountId(account.getAccountId())) {
                continue; // Skip duplicates
            }

            BankAccount bankAccount = new BankAccount();
            bankAccount.setUserId(userId);
            bankAccount.setPlaidAccountId(account.getAccountId());
            bankAccount.setPlaidAccessToken(accessToken); // TODO: Encrypt in production!
            bankAccount.setInstitutionName(institutionName);
            bankAccount.setInstitutionId(institutionId);
            bankAccount.setAccountName(account.getName());
            bankAccount.setAccountType(account.getType().toString());
            bankAccount.setAccountSubtype(account.getSubtype() != null ?
                    account.getSubtype().toString() : null);

            // Set balances
            if (account.getBalances() != null) {
                if (account.getBalances().getCurrent() != null) {
                    bankAccount.setCurrentBalance(account.getBalances().getCurrent());
                }
                if (account.getBalances().getAvailable() != null) {
                    bankAccount.setAvailableBalance(account.getBalances().getAvailable());
                }
                if (account.getBalances().getIsoCurrencyCode() != null) {
                    bankAccount.setCurrencyCode(account.getBalances().getIsoCurrencyCode());
                }
            }

            bankAccount.setLastSyncedAt(LocalDateTime.now());
            bankAccount.setIsActive(true);

            savedAccounts.add(bankAccountRepository.save(bankAccount));
        }

        return savedAccounts;
    }

    /**
     * Get all bank accounts for a user
     */
    public List<BankAccount> getUserBankAccounts(UUID userId) {
        return bankAccountRepository.findByUserIdAndIsActive(userId, true);
    }

    /**
     * Delete/deactivate a bank account
     */
    public void disconnectBankAccount(UUID accountId, UUID userId) {
        BankAccount account = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        // Verify ownership
        if (!account.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized");
        }

        // Soft delete
        account.setIsActive(false);
        bankAccountRepository.save(account);
    }
}
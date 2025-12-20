// src/main/java/com/financecoach/userservice/service/PlaidService.java
package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.repository.BankAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(PlaidService.class);

    private final PlaidApi plaidClient;
    private final BankAccountRepository bankAccountRepository;
    @Autowired
    private MetricsService metricsService;

    @Autowired
    public PlaidService(PlaidApi plaidClient, BankAccountRepository bankAccountRepository) {
        this.plaidClient = plaidClient;
        this.bankAccountRepository = bankAccountRepository;
    }

    /**
     * Create a link token for Plaid Link
     */
    public String createLinkToken(UUID userId, String username) {
        logger.info("Creating Plaid link token for user: {}", userId);

        try {
            LinkTokenCreateRequestUser user = new LinkTokenCreateRequestUser()
                    .clientUserId(userId.toString());

            LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                    .user(user)
                    .clientName("Finance Coach")
                    .products(Arrays.asList(Products.TRANSACTIONS))
                    .countryCodes(Arrays.asList(CountryCode.US))
                    .language("en");

            Response<LinkTokenCreateResponse> response = plaidClient
                    .linkTokenCreate(request)
                    .execute();

            if (response.isSuccessful() && response.body() != null) {
                logger.info("Link token created successfully for user: {}", userId);
                return response.body().getLinkToken();
            }

            String errorMsg = response.errorBody() != null
                    ? response.errorBody().string()
                    : "Unknown error";

            logger.error("Failed to create link token for user: {} - Error: {}", userId, errorMsg);
            throw new PlaidIntegrationException("Failed to create link token: " + errorMsg);

        } catch (IOException e) {
            logger.error("Network error creating link token for user: {}", userId, e);
            throw new PlaidIntegrationException("Network error creating link token", e);
        }
    }


    /**
     * Exchange public token for access token and save account
     */
    public List<BankAccount> exchangePublicToken(UUID userId, String publicToken) {
        long startTime = System.currentTimeMillis();
        logger.info("Exchanging public token for user: {}", userId);

        try {
            // Exchange public token for access token
            ItemPublicTokenExchangeRequest exchangeRequest = new ItemPublicTokenExchangeRequest()
                    .publicToken(publicToken);

            Response<ItemPublicTokenExchangeResponse> exchangeResponse = plaidClient
                    .itemPublicTokenExchange(exchangeRequest)
                    .execute();

            if (!exchangeResponse.isSuccessful() || exchangeResponse.body() == null) {
                String errorMsg = exchangeResponse.errorBody() != null
                        ? exchangeResponse.errorBody().string()
                        : "Unknown error";
                logger.error("Failed to exchange token for user: {} - Error: {}", userId, errorMsg);
                throw new PlaidIntegrationException("Failed to exchange token: " + errorMsg);
            }

            String accessToken = exchangeResponse.body().getAccessToken();
            logger.debug("Access token obtained for user: {}", userId);

            // Get account details
            AccountsGetRequest accountsRequest = new AccountsGetRequest()
                    .accessToken(accessToken);

            Response<AccountsGetResponse> accountsResponse = plaidClient
                    .accountsGet(accountsRequest)
                    .execute();

            if (!accountsResponse.isSuccessful() || accountsResponse.body() == null) {
                String errorMsg = accountsResponse.errorBody() != null
                        ? accountsResponse.errorBody().string()
                        : "Unknown error";
                logger.error("Failed to get accounts for user: {} - Error: {}", userId, errorMsg);
                throw new PlaidIntegrationException("Failed to get accounts: " + errorMsg);
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
                    logger.warn("Failed to fetch institution name for: {} - using 'Unknown'", institutionId);
                }
            }

            // Save accounts to database
            List<BankAccount> savedAccounts = new ArrayList<>();
            int accountCount = 0;

            for (AccountBase account : accountsResponse.body().getAccounts()) {
                // Check if account already exists
                if (bankAccountRepository.existsByPlaidAccountId(account.getAccountId())) {
                    logger.debug("Account already exists, skipping: {}", account.getAccountId());
                    continue;
                }

                BankAccount bankAccount = new BankAccount();
                bankAccount.setUserId(userId);
                bankAccount.setPlaidAccountId(account.getAccountId());
                bankAccount.setPlaidAccessToken(accessToken);
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
                accountCount++;

                metricsService.recordBankAccountConnected();
            }

            long duration = System.currentTimeMillis() - startTime;
            metricsService.recordPlaidApiDuration(duration);

            logger.info("Successfully connected {} bank account(s) for user: {} in {}ms",
                    accountCount, userId, duration);

            return savedAccounts;

        } catch (IOException e) {
            logger.error("Network error exchanging token for user: {}", userId, e);
            throw new PlaidIntegrationException("Network error exchanging token", e);
        }
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
                .orElseThrow(() -> new BankAccountNotFoundException(accountId));

        if (!account.getUserId().equals(userId)) {
            throw new UnauthorizedAccessException("bank account");
        }

        account.setIsActive(false);
        bankAccountRepository.save(account);
    }
}
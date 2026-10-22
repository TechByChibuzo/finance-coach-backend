// src/main/java/com/financecoach/userservice/service/PlaidService.java
package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.FeatureNotAvailableException;
import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.PlaidItemRepository;
import com.financecoach.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PlaidService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidService.class);

    private final PlaidApi plaidClient;
    private final BankAccountRepository bankAccountRepository;
    private final PlaidItemRepository plaidItemRepository;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    @Autowired
    private MetricsService metricsService;

    @Autowired
    public PlaidService(PlaidApi plaidClient,
                        BankAccountRepository bankAccountRepository,
                        PlaidItemRepository plaidItemRepository,
                        SubscriptionService subscriptionService,
                        UserRepository userRepository,
                        TransactionTemplate transactionTemplate) {
        this.plaidClient = plaidClient;
        this.bankAccountRepository = bankAccountRepository;
        this.plaidItemRepository = plaidItemRepository;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Create a link token for Plaid Link
     */
    public String createLinkToken(UUID userId, String username) {
        logger.info("Creating Plaid link token for user: {}", userId);

        // Check before the user goes through the bank login, not after
        ensureCanConnectAnotherBank(userId);

        try {
            LinkTokenCreateRequestUser user = new LinkTokenCreateRequestUser()
                    .clientUserId(userId.toString());

            LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                    .user(user)
                    .clientName("Finance Coach")
                    // Every product in products is required, so a bank without investment accounts
                    // would refuse to connect; investments are added only where available
                    .products(List.of(Products.TRANSACTIONS))
                    .optionalProducts(List.of(Products.INVESTMENTS))
                    .countryCodes(List.of(CountryCode.US))
                    .transactions(new LinkTokenTransactions().daysRequested(730))
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

        ensureCanConnectAnotherBank(userId);

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
            String plaidItemId = exchangeResponse.body().getItemId();
            logger.debug("Access token obtained for user: {}, item: {}", userId, plaidItemId);

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
                            .countryCodes(List.of(CountryCode.US));

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

            List<AccountBase> plaidAccounts = accountsResponse.body().getAccounts();
            String bankName = institutionName;
            boolean newItem = plaidItemRepository.findByPlaidItemId(plaidItemId).isEmpty();

            // Re-check the limit and save under a lock on the user row, so two connections
            // finishing at the same time can't both pass the check above
            List<BankAccount> savedAccounts;
            try {
                savedAccounts = transactionTemplate.execute(status -> {
                    userRepository.lockById(userId);
                    ensureCanConnectAnotherBank(userId);
                    PlaidItem item = saveItem(userId, plaidItemId, accessToken, institutionId, bankName);
                    return saveNewAccounts(userId, item, accessToken, institutionId, bankName, plaidAccounts);
                });
            } catch (FeatureNotAvailableException e) {
                if (newItem) {
                    revokeQuietly(accessToken); // don't leave a bank link we won't use
                }
                throw e;
            }

            savedAccounts.forEach(account -> metricsService.recordBankAccountConnected());
            int accountCount = savedAccounts.size();

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

    private List<BankAccount> saveNewAccounts(UUID userId, PlaidItem item, String accessToken,
                                              String institutionId, String institutionName,
                                              List<AccountBase> plaidAccounts) {
        List<BankAccount> savedAccounts = new ArrayList<>();

        for (AccountBase account : plaidAccounts) {
            // Check if account already exists
            if (bankAccountRepository.existsByPlaidAccountId(account.getAccountId())) {
                logger.debug("Account already exists, skipping: {}", account.getAccountId());
                continue;
            }

            BankAccount bankAccount = new BankAccount();
            bankAccount.setUserId(userId);
            bankAccount.setItemId(item.getId());
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
                Double currentBalance = account.getBalances().getCurrent();
                if (currentBalance != null) {
                    bankAccount.setCurrentBalance(BigDecimal.valueOf(currentBalance));
                }
                Double availableBalance = account.getBalances().getAvailable();
                if (availableBalance != null) {
                    bankAccount.setAvailableBalance(BigDecimal.valueOf(availableBalance));
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

    private void revokeQuietly(String accessToken) {
        try {
            plaidClient.itemRemove(new ItemRemoveRequest().accessToken(accessToken)).execute();
        } catch (Exception e) {
            logger.warn("Failed to revoke Plaid item refused by the plan limit", e);
        }
    }

    /**
     * Plan limits count bank connections (Plaid items), not accounts: one bank login
     * usually brings several accounts (checking, savings, card...).
     */
    private void ensureCanConnectAnotherBank(UUID userId) {
        int limit = subscriptionService.getLimit(userId, "bank_account");
        if (limit >= 0 && bankAccountRepository.countBankConnections(userId) >= limit) {
            throw new FeatureNotAvailableException("Your plan allows " + limit
                    + (limit == 1 ? " bank connection" : " bank connections")
                    + ". Upgrade to connect more banks.");
        }
    }

    private PlaidItem saveItem(UUID userId, String plaidItemId, String accessToken,
                               String institutionId, String institutionName) {
        PlaidItem item = plaidItemRepository.findByPlaidItemId(plaidItemId).orElseGet(PlaidItem::new);
        if (item.getUserId() != null && !item.getUserId().equals(userId)) {
            throw new UnauthorizedAccessException("Plaid item");
        }
        item.setUserId(userId);
        item.setPlaidItemId(plaidItemId);
        item.setAccessToken(accessToken);
        item.setInstitutionId(institutionId);
        item.setInstitutionName(institutionName);
        return plaidItemRepository.save(item);
    }

    /**
     * Get the Plaid item an account belongs to. Accounts linked before items were stored
     * have no itemId, so look the item up from Plaid once and attach it.
     */
    public PlaidItem getItem(BankAccount account) {
        if (account.getItemId() != null) {
            PlaidItem item = plaidItemRepository.findById(account.getItemId())
                    .orElseThrow(() -> new PlaidIntegrationException(
                            "Plaid item not found for account: " + account.getId()));
            if (!item.getUserId().equals(account.getUserId())) {
                throw new UnauthorizedAccessException("Plaid item");
            }
            return item;
        }

        try {
            Response<ItemGetResponse> response = plaidClient
                    .itemGet(new ItemGetRequest().accessToken(account.getPlaidAccessToken()))
                    .execute();

            if (!response.isSuccessful() || response.body() == null) {
                String errorMsg = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                throw new PlaidIntegrationException("Failed to get Plaid item: " + errorMsg);
            }

            PlaidItem item = plaidItemRepository.findByPlaidItemId(response.body().getItem().getItemId())
                    .orElseGet(() -> saveItem(account.getUserId(), response.body().getItem().getItemId(),
                            account.getPlaidAccessToken(), account.getInstitutionId(),
                            account.getInstitutionName()));

            account.setItemId(item.getId());
            bankAccountRepository.save(account);
            logger.info("Attached Plaid item {} to account {}", item.getId(), account.getId());
            return item;

        } catch (IOException e) {
            throw new PlaidIntegrationException("Network error getting Plaid item", e);
        }
    }

    /**
     * Distinct items for a set of accounts, so each bank login is synced once
     */
    public List<PlaidItem> getItems(List<BankAccount> accounts) {
        Map<UUID, PlaidItem> items = new LinkedHashMap<>();
        for (BankAccount account : accounts) {
            PlaidItem item = getItem(account);
            items.putIfAbsent(item.getId(), item);
        }
        return new ArrayList<>(items.values());
    }

    /**
     * Active accounts of an item, keyed by Plaid account ID
     */
    public Map<String, BankAccount> getActiveAccountsByPlaidId(PlaidItem item) {
        Map<String, BankAccount> accounts = new LinkedHashMap<>();
        for (BankAccount account : bankAccountRepository.findByItemIdAndIsActive(item.getId(), true)) {
            accounts.put(account.getPlaidAccountId(), account);
        }
        return accounts;
    }

    /**
     * Refresh current/available balances for all of an item's accounts via /accounts/get.
     * Balances were otherwise only written when the account was first linked.
     */
    public void refreshBalances(PlaidItem item) {
        try {
            Response<AccountsGetResponse> response = plaidClient
                    .accountsGet(new AccountsGetRequest().accessToken(item.getAccessToken()))
                    .execute();

            if (!response.isSuccessful() || response.body() == null) {
                String errorMsg = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                throw new PlaidIntegrationException("Failed to get balances: " + errorMsg);
            }

            applyBalances(item, response.body().getAccounts());

        } catch (IOException e) {
            throw new PlaidIntegrationException("Network error getting balances", e);
        }
    }

    /**
     * Copy balances from Plaid's account objects onto the item's own accounts.
     * Only values Plaid actually sends are written, so a missing field never wipes a stored balance.
     */
    void applyBalances(PlaidItem item, List<AccountBase> plaidAccounts) {
        if (plaidAccounts == null) {
            return;
        }

        Map<String, BankAccount> accountsByPlaidId = getActiveAccountsByPlaidId(item);
        for (AccountBase plaidAccount : plaidAccounts) {
            BankAccount account = accountsByPlaidId.get(plaidAccount.getAccountId());
            if (account == null || plaidAccount.getBalances() == null) {
                continue;
            }

            AccountBalance balances = plaidAccount.getBalances();
            if (balances.getCurrent() != null) {
                account.setCurrentBalance(BigDecimal.valueOf(balances.getCurrent()));
            }
            if (balances.getAvailable() != null) {
                account.setAvailableBalance(BigDecimal.valueOf(balances.getAvailable()));
            }
            if (balances.getIsoCurrencyCode() != null) {
                account.setCurrencyCode(balances.getIsoCurrencyCode());
            }
            bankAccountRepository.save(account);
        }
    }

    // Plaid errors meaning the item is already gone or the token no longer works
    private static final Set<String> ALREADY_REVOKED_ERRORS =
            Set.of("ITEM_NOT_FOUND", "INVALID_ACCESS_TOKEN", "INVALID_ITEM_ID");

    /**
     * Revoke every Plaid item (bank login) the user has linked, so Plaid stops billing for them
     * and their access tokens stop working. Items Plaid reports as already gone count as removed.
     *
     * @return the number of items revoked
     * @throws PlaidIntegrationException on any other failure (network, rate limit, Plaid outage),
     *         so account deletion stops and can be retried instead of leaving access live
     */
    public int removeAllItemsForUser(UUID userId) {
        // Items, plus tokens of accounts linked before items were stored
        Set<String> accessTokens = new LinkedHashSet<>();
        plaidItemRepository.findByUserId(userId).forEach(item -> accessTokens.add(item.getAccessToken()));
        bankAccountRepository.findByUserId(userId).stream()
                .filter(account -> account.getItemId() == null)
                .forEach(account -> accessTokens.add(account.getPlaidAccessToken()));

        int removed = 0;
        for (String accessToken : accessTokens) {
            try {
                Response<ItemRemoveResponse> response = plaidClient
                        .itemRemove(new ItemRemoveRequest().accessToken(accessToken))
                        .execute();

                if (!response.isSuccessful()) {
                    String errorBody = response.errorBody() != null ? response.errorBody().string() : "";
                    boolean alreadyRevoked = ALREADY_REVOKED_ERRORS.stream().anyMatch(errorBody::contains);
                    if (!alreadyRevoked) {
                        throw new PlaidIntegrationException(
                                "Failed to revoke Plaid item (HTTP " + response.code() + ")");
                    }
                    logger.info("Plaid item for user {} was already revoked", userId);
                }
                removed++;
            } catch (IOException e) {
                throw new PlaidIntegrationException("Network error revoking Plaid item", e);
            }
        }

        logger.info("Revoked {} Plaid items for user {}", removed, userId);
        return removed;
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

    /**
     * Get investment holdings from Plaid
     */
    public InvestmentsHoldingsGetResponse getHoldings(String accessToken) {
        logger.info("Fetching investment holdings from Plaid");

        try {
            InvestmentsHoldingsGetRequest request = new InvestmentsHoldingsGetRequest()
                    .accessToken(accessToken);

            Response<InvestmentsHoldingsGetResponse> response = plaidClient
                    .investmentsHoldingsGet(request)
                    .execute();

            if (!response.isSuccessful()) {
                String errorBody = "No error body";
                try {
                    if (response.errorBody() != null) {
                        errorBody = response.errorBody().string();
                    }
                } catch (IOException e) {
                    logger.error("Could not read error body", e);
                }

                logger.error("=== PLAID API ERROR ===");
                logger.error("Status Code: {}", response.code());
                logger.error("Status Message: {}", response.message());
                logger.error("Error Body: {}", errorBody);
                logger.error("=====================");

                throw new PlaidIntegrationException(
                        String.format("Plaid API error [%d]: %s", response.code(), errorBody)
                );
            }

            InvestmentsHoldingsGetResponse holdingsResponse = response.body();

            logger.info("Successfully fetched holdings from Plaid");

            return holdingsResponse;

        } catch (IOException e) {
            logger.error("Failed to fetch holdings from Plaid", e);
            throw new PlaidIntegrationException("Unable to fetch investment holdings", e);
        }
    }

    /**
     * Get investment transactions (buys, sells, dividends, etc.)
     */
    public InvestmentsTransactionsGetResponse getInvestmentTransactions(
            String accessToken,
            LocalDate startDate,
            LocalDate endDate) {

        logger.info("Fetching investment transactions from {} to {}", startDate, endDate);

        try {
            InvestmentsTransactionsGetRequest request = new InvestmentsTransactionsGetRequest()
                    .accessToken(accessToken)
                    .startDate(startDate)
                    .endDate(endDate);

            Response<InvestmentsTransactionsGetResponse> response = plaidClient
                    .investmentsTransactionsGet(request)
                    .execute();

            if (!response.isSuccessful()) {
                logger.error("Failed to fetch investment transactions: {}", response.message());
                throw new PlaidIntegrationException("Failed to fetch investment transactions: " + response.message());
            }

            InvestmentsTransactionsGetResponse transactionsResponse = response.body();

            logger.info("Successfully fetched investment transactions");

            return transactionsResponse;

        } catch (IOException e) {
            logger.error("Failed to fetch investment transactions from Plaid", e);
            throw new PlaidIntegrationException("Unable to fetch investment transactions", e);
        }
    }
}
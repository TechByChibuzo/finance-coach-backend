package com.financecoach.backend.service;

import com.financecoach.backend.exception.BankAccountNotFoundException;
import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.PlaidItemRepository;
import com.financecoach.backend.repository.UserRepository;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountSubtype;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.Institution;
import com.plaid.client.model.InstitutionsGetByIdResponse;
import com.plaid.client.model.InvestmentsHoldingsGetResponse;
import com.plaid.client.model.InvestmentsTransactionsGetResponse;
import com.plaid.client.model.Item;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Linking banks (link token + public token exchange), item lookup and backfill,
 * disconnecting accounts, and the investment endpoints.
 */
@ExtendWith(MockitoExtension.class)
class PlaidServiceLinkingTest {

    @Mock private PlaidApi plaidClient;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private PlaidItemRepository plaidItemRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private MetricsService metricsService;
    @Mock private InstitutionIconFetcher iconFetcher;

    private PlaidService plaidService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        plaidService = new PlaidService(plaidClient, bankAccountRepository, plaidItemRepository, subscriptionService,
                userRepository, new TransactionTemplate(transactionManager), metricsService, iconFetcher);
        userId = UUID.randomUUID();
        // Unlimited bank connections unless a test says otherwise
        org.mockito.Mockito.lenient().when(subscriptionService.getLimit(any(), org.mockito.ArgumentMatchers.eq("bank_account")))
                .thenReturn(-1);
    }

    // --- link token ---

    @Test
    void createLinkToken_requiresTransactions_withInvestmentsOptional_andTwoYearsOfHistory() throws Exception {
        when(plaidClient.linkTokenCreate(any())).thenReturn(ok(new LinkTokenCreateResponse().linkToken("link-123")));

        String token = plaidService.createLinkToken(userId, "user");

        ArgumentCaptor<LinkTokenCreateRequest> request = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidClient).linkTokenCreate(request.capture());
        assertThat(token).isEqualTo("link-123");
        assertThat(request.getValue().getUser().getClientUserId()).isEqualTo(userId.toString());
        assertThat(request.getValue().getProducts()).containsExactly(Products.TRANSACTIONS);
        // Banks without investment accounts must still be able to connect
        assertThat(request.getValue().getOptionalProducts()).containsExactly(Products.INVESTMENTS);
        assertThat(request.getValue().getTransactions().getDaysRequested()).isEqualTo(730);
    }

    @Test
    void createLinkToken_whenPlaidRejects_orIsUnreachable_throws() throws Exception {
        when(plaidClient.linkTokenCreate(any()))
                .thenReturn(error(400, "INVALID_FIELD"))
                .thenReturn(networkFailure());

        assertThatThrownBy(() -> plaidService.createLinkToken(userId, "user"))
                .isInstanceOf(PlaidIntegrationException.class).hasMessageContaining("INVALID_FIELD");
        assertThatThrownBy(() -> plaidService.createLinkToken(userId, "user"))
                .isInstanceOf(PlaidIntegrationException.class).hasMessageContaining("Network");
    }

    @Test
    void createLinkToken_atTheBankConnectionLimit_isRefusedBeforePlaidLinkOpens() throws Exception {
        when(subscriptionService.getLimit(userId, "bank_account")).thenReturn(1);
        when(bankAccountRepository.countBankConnections(userId)).thenReturn(1L);

        assertThatThrownBy(() -> plaidService.createLinkToken(userId, "user"))
                .isInstanceOf(com.financecoach.backend.exception.FeatureNotAvailableException.class)
                .hasMessageContaining("1 bank connection");
        verify(plaidClient, never()).linkTokenCreate(any());
    }

    @Test
    void createLinkToken_underTheLimit_isAllowed() throws Exception {
        when(subscriptionService.getLimit(userId, "bank_account")).thenReturn(5);
        when(bankAccountRepository.countBankConnections(userId)).thenReturn(4L);
        when(plaidClient.linkTokenCreate(any())).thenReturn(ok(new LinkTokenCreateResponse().linkToken("link-123")));

        assertThat(plaidService.createLinkToken(userId, "user")).isEqualTo("link-123");
    }

    @Test
    void createUpdateLinkToken_opensLinkOnTheExistingConnection_evenAtTheLimit() throws Exception {
        PlaidItem chase = item(userId);
        chase.setAccessToken("access-chase");
        when(plaidItemRepository.findById(chase.getId())).thenReturn(Optional.of(chase));
        org.mockito.Mockito.lenient().when(subscriptionService.getLimit(userId, "bank_account")).thenReturn(1);
        org.mockito.Mockito.lenient().when(bankAccountRepository.countBankConnections(userId)).thenReturn(1L);
        when(plaidClient.linkTokenCreate(any())).thenReturn(ok(new LinkTokenCreateResponse().linkToken("link-update")));

        assertThat(plaidService.createUpdateLinkToken(userId, chase.getId())).isEqualTo("link-update");

        ArgumentCaptor<LinkTokenCreateRequest> request = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidClient).linkTokenCreate(request.capture());
        assertThat(request.getValue().getAccessToken()).isEqualTo("access-chase");
        assertThat(request.getValue().getProducts()).isNullOrEmpty();
        assertThat(request.getValue().getUser().getClientUserId()).isEqualTo(userId.toString());
    }

    @Test
    void createUpdateLinkToken_forAnotherUsersConnection_isRejected_andUnknown_isNotFound() {
        PlaidItem someoneElses = item(UUID.randomUUID());
        when(plaidItemRepository.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));
        UUID missing = UUID.randomUUID();
        when(plaidItemRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> plaidService.createUpdateLinkToken(userId, someoneElses.getId()))
                .isInstanceOf(com.financecoach.backend.exception.UnauthorizedAccessException.class);
        assertThatThrownBy(() -> plaidService.markReconnected(userId, someoneElses.getId()))
                .isInstanceOf(com.financecoach.backend.exception.UnauthorizedAccessException.class);
        assertThatThrownBy(() -> plaidService.createUpdateLinkToken(userId, missing))
                .isInstanceOf(com.financecoach.backend.exception.BankAccountNotFoundException.class);
        verify(plaidItemRepository, never()).save(any());
    }

    @Test
    void markReconnected_clearsTheFlag() {
        PlaidItem chase = item(userId);
        chase.setNeedsReconnect(true);
        when(plaidItemRepository.findById(chase.getId())).thenReturn(Optional.of(chase));

        plaidService.markReconnected(userId, chase.getId());

        assertThat(chase.getNeedsReconnect()).isFalse();
        verify(plaidItemRepository).save(chase);
    }

    @Test
    void exchangePublicToken_atTheLimit_isRefusedWithoutExchanging() throws Exception {
        when(subscriptionService.getLimit(userId, "bank_account")).thenReturn(1);
        when(bankAccountRepository.countBankConnections(userId)).thenReturn(1L);

        assertThatThrownBy(() -> plaidService.exchangePublicToken(userId, "public-token"))
                .hasMessageContaining("Upgrade to connect more banks");
        verify(plaidClient, never()).itemPublicTokenExchange(any());
    }

    @Test
    void exchangePublicToken_whenAnotherConnectionFinishedFirst_isRefusedUnderTheLock_andRevokesTheNewItem() throws Exception {
        when(subscriptionService.getLimit(userId, "bank_account")).thenReturn(1);
        // Free when the request starts; taken by a concurrent connection by the time it saves
        when(bankAccountRepository.countBankConnections(userId)).thenReturn(0L, 1L);
        stubExchange("access-2", "item-2");
        when(plaidClient.accountsGet(any())).thenReturn(ok(new AccountsGetResponse()
                .item(new Item())
                .accounts(List.of(plaidAccount("acc-1", AccountType.DEPOSITORY, null, null, null)))));
        when(plaidItemRepository.findByPlaidItemId("item-2")).thenReturn(Optional.empty());
        when(plaidClient.itemRemove(any())).thenReturn(ok(new com.plaid.client.model.ItemRemoveResponse()));

        assertThatThrownBy(() -> plaidService.exchangePublicToken(userId, "public-token"))
                .hasMessageContaining("Upgrade to connect more banks");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(userRepository, bankAccountRepository);
        order.verify(userRepository).lockById(userId);
        order.verify(bankAccountRepository).countBankConnections(userId);
        verify(plaidItemRepository, never()).save(any());
        verify(bankAccountRepository, never()).save(any());
        verify(plaidClient).itemRemove(any());
    }

    @Test
    void countBankConnections_includesAccountsLinkedBeforeItemsWereStored() {
        BankAccountRepository repository = mock(BankAccountRepository.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(1L).when(repository).countConnectedItems(userId);
        org.mockito.Mockito.doReturn(2L).when(repository).countLegacyConnections(userId);

        assertThat(repository.countBankConnections(userId)).isEqualTo(3L);
    }

    @Test
    void countLegacyConnections_countsEachDecryptedAccessTokenOnce() {
        // Stored tokens are salted, so the same token encrypts differently on every account;
        // two accounts from one legacy connection must still count as one
        BankAccountRepository repository = mock(BankAccountRepository.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        BankAccount checking = new BankAccount();
        checking.setPlaidAccessToken("access-legacy");
        BankAccount savings = new BankAccount();
        savings.setPlaidAccessToken("access-legacy");
        BankAccount otherBank = new BankAccount();
        otherBank.setPlaidAccessToken("access-other");
        BankAccount linkedViaItem = new BankAccount();
        linkedViaItem.setItemId(UUID.randomUUID());
        linkedViaItem.setPlaidAccessToken("access-new");
        org.mockito.Mockito.doReturn(List.of(checking, savings, otherBank, linkedViaItem))
                .when(repository).findByUserIdAndIsActive(userId, true);

        assertThat(repository.countLegacyConnections(userId)).isEqualTo(2L);
    }

    // --- public token exchange ---

    @Test
    void exchangePublicToken_savesTheItemAndEachNewAccount() throws Exception {
        stubExchange("access-1", "item-1");
        when(plaidClient.accountsGet(any())).thenReturn(ok(new AccountsGetResponse()
                .item(new Item().institutionId("ins_1"))
                .accounts(List.of(
                        plaidAccount("acc-checking", AccountType.DEPOSITORY, AccountSubtype.CHECKING, 1200.50, 1100.00),
                        plaidAccount("acc-card", AccountType.CREDIT, AccountSubtype.CREDIT_CARD, 300.00, null)))));
        when(plaidClient.institutionsGetById(any()))
                .thenReturn(ok(new InstitutionsGetByIdResponse().institution(new Institution().name("Chase"))));
        when(plaidItemRepository.findByPlaidItemId("item-1")).thenReturn(Optional.empty());
        when(plaidItemRepository.save(any())).thenAnswer(inv -> {
            PlaidItem item = inv.getArgument(0);
            item.setId(UUID.randomUUID());
            return item;
        });
        when(bankAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BankAccount> accounts = plaidService.exchangePublicToken(userId, "public-token");

        ArgumentCaptor<PlaidItem> item = ArgumentCaptor.forClass(PlaidItem.class);
        verify(plaidItemRepository).save(item.capture());
        assertThat(item.getValue().getUserId()).isEqualTo(userId);
        assertThat(item.getValue().getAccessToken()).isEqualTo("access-1");
        assertThat(item.getValue().getInstitutionName()).isEqualTo("Chase");

        assertThat(accounts).hasSize(2);
        BankAccount checking = accounts.get(0);
        assertThat(checking.getItemId()).isEqualTo(item.getValue().getId());
        assertThat(checking.getInstitutionName()).isEqualTo("Chase");
        assertThat(checking.getAccountType()).isEqualTo("depository");
        assertThat(checking.getAccountSubtype()).isEqualTo("checking");
        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("1200.50");
        assertThat(checking.getAvailableBalance()).isEqualByComparingTo("1100.00");
        assertThat(checking.getCurrencyCode()).isEqualTo("USD");
        assertThat(accounts.get(1).getAvailableBalance()).isNull();
        verify(metricsService, times(2)).recordBankAccountConnected();
    }

    @Test
    void exchangePublicToken_skipsAccountsAlreadyLinked_andFallsBackToUnknownInstitution() throws Exception {
        stubExchange("access-1", "item-1");
        when(plaidClient.accountsGet(any())).thenReturn(ok(new AccountsGetResponse()
                .item(new Item().institutionId("ins_1"))
                .accounts(List.of(
                        plaidAccount("acc-known", AccountType.DEPOSITORY, null, null, null),
                        plaidAccount("acc-new", AccountType.DEPOSITORY, null, null, null)))));
        when(plaidClient.institutionsGetById(any())).thenThrow(new RuntimeException("institution lookup down"));
        when(plaidItemRepository.findByPlaidItemId("item-1")).thenReturn(Optional.empty());
        when(plaidItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        BankAccount alreadyLinked = new BankAccount();
        alreadyLinked.setUserId(userId);
        alreadyLinked.setIsActive(true);
        when(bankAccountRepository.findByPlaidAccountId("acc-known")).thenReturn(Optional.of(alreadyLinked));
        when(bankAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BankAccount> accounts = plaidService.exchangePublicToken(userId, "public-token");

        assertThat(accounts).extracting(BankAccount::getPlaidAccountId).containsExactly("acc-new");
        assertThat(accounts.get(0).getInstitutionName()).isEqualTo("Unknown");
        assertThat(accounts.get(0).getCurrentBalance()).isNull();
    }

    @Test
    void exchangePublicToken_anAccountTheUserDisconnected_isReactivatedOnTheNewLogin_keepingItsHistory() throws Exception {
        BankAccount disconnected = new BankAccount();
        disconnected.setId(UUID.randomUUID());
        disconnected.setUserId(userId);
        disconnected.setPlaidAccountId("acc-back");
        disconnected.setItemId(UUID.randomUUID());
        disconnected.setPlaidAccessToken("access-old");
        disconnected.setIsActive(false);
        stubExchange("access-3", "item-3");
        when(plaidClient.accountsGet(any())).thenReturn(ok(new AccountsGetResponse()
                .item(new Item().institutionId("ins_1"))
                .accounts(List.of(plaidAccount("acc-back", AccountType.DEPOSITORY, AccountSubtype.CHECKING, 820.00, 800.00)))));
        when(plaidClient.institutionsGetById(any()))
                .thenReturn(ok(new InstitutionsGetByIdResponse().institution(new Institution().name("Chase"))));
        when(plaidItemRepository.findByPlaidItemId("item-3")).thenReturn(Optional.empty());
        UUID newItemId = UUID.randomUUID();
        when(plaidItemRepository.save(any())).thenAnswer(inv -> {
            PlaidItem item = inv.getArgument(0);
            item.setId(newItemId);
            return item;
        });
        when(bankAccountRepository.findByPlaidAccountId("acc-back")).thenReturn(Optional.of(disconnected));
        when(bankAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BankAccount> accounts = plaidService.exchangePublicToken(userId, "public-token");

        // The same row (and so its transactions), now on the new login
        assertThat(accounts).containsExactly(disconnected);
        assertThat(disconnected.getIsActive()).isTrue();
        assertThat(disconnected.getItemId()).isEqualTo(newItemId);
        assertThat(disconnected.getPlaidAccessToken()).isEqualTo("access-3");
        assertThat(disconnected.getCurrentBalance()).isEqualByComparingTo("820.00");
        verify(plaidClient, never()).itemRemove(any());
    }

    @Test
    void exchangePublicToken_anAccountIdBelongingToAnotherUser_isNeverTakenOver() throws Exception {
        BankAccount someoneElses = new BankAccount();
        someoneElses.setUserId(UUID.randomUUID());
        someoneElses.setPlaidAccountId("acc-theirs");
        someoneElses.setIsActive(false);
        stubExchange("access-4", "item-4");
        when(plaidClient.accountsGet(any())).thenReturn(ok(new AccountsGetResponse()
                .item(new Item().institutionId("ins_1"))
                .accounts(List.of(
                        plaidAccount("acc-theirs", AccountType.DEPOSITORY, null, null, null),
                        plaidAccount("acc-mine", AccountType.DEPOSITORY, null, null, null)))));
        when(plaidClient.institutionsGetById(any()))
                .thenReturn(ok(new InstitutionsGetByIdResponse().institution(new Institution().name("Chase"))));
        when(plaidItemRepository.findByPlaidItemId("item-4")).thenReturn(Optional.empty());
        when(plaidItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankAccountRepository.findByPlaidAccountId("acc-theirs")).thenReturn(Optional.of(someoneElses));
        when(bankAccountRepository.findByPlaidAccountId("acc-mine")).thenReturn(Optional.empty());
        when(bankAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BankAccount> accounts = plaidService.exchangePublicToken(userId, "public-token");

        assertThat(accounts).extracting(BankAccount::getPlaidAccountId).containsExactly("acc-mine");
        assertThat(someoneElses.getIsActive()).isFalse();
    }

    @Test
    void exchangePublicToken_cardAlreadyConnectedThroughAnotherLogin_isSkipped() throws Exception {
        BankAccount existingCard = card(UUID.randomUUID(), "1234", null);
        stubExchange("access-2", "item-2");
        when(plaidClient.accountsGet(any())).thenReturn(ok(new AccountsGetResponse()
                .item(new Item().institutionId("ins_56"))
                .accounts(List.of(
                        plaidAccount("acc-same-card", AccountType.CREDIT, AccountSubtype.CREDIT_CARD, 1025.04, null).mask("1234"),
                        plaidAccount("acc-other-card", AccountType.CREDIT, AccountSubtype.CREDIT_CARD, 264.33, null).mask("9876")))));
        when(plaidClient.institutionsGetById(any()))
                .thenReturn(ok(new InstitutionsGetByIdResponse().institution(new Institution().name("Chase"))));
        when(plaidItemRepository.findByPlaidItemId("item-2")).thenReturn(Optional.empty());
        when(plaidItemRepository.save(any())).thenAnswer(inv -> {
            PlaidItem item = inv.getArgument(0);
            item.setId(UUID.randomUUID());
            return item;
        });
        when(bankAccountRepository.findByUserIdAndIsActive(userId, true)).thenReturn(List.of(existingCard));
        when(bankAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BankAccount> accounts = plaidService.exchangePublicToken(userId, "public-token");

        assertThat(accounts).extracting(BankAccount::getPlaidAccountId).containsExactly("acc-other-card");
        assertThat(accounts.get(0).getMask()).isEqualTo("9876");
        verify(plaidClient, never()).itemRemove(any());
    }

    @Test
    void exchangePublicToken_everyAccountAlreadyConnected_revokesTheNewLoginAndSaysSo() throws Exception {
        BankAccount existingCard = card(UUID.randomUUID(), "1234", null);
        stubExchange("access-2", "item-2");
        when(plaidClient.accountsGet(any())).thenReturn(ok(new AccountsGetResponse()
                .item(new Item().institutionId("ins_56"))
                .accounts(List.of(plaidAccount("acc-same-card", AccountType.CREDIT, AccountSubtype.CREDIT_CARD, 1025.04, null).mask("1234")))));
        when(plaidClient.institutionsGetById(any()))
                .thenReturn(ok(new InstitutionsGetByIdResponse().institution(new Institution().name("Chase"))));
        PlaidItem saved = item(userId);
        when(plaidItemRepository.findByPlaidItemId("item-2")).thenReturn(Optional.empty(), Optional.empty(), Optional.of(saved));
        when(plaidItemRepository.save(any())).thenReturn(saved);
        when(bankAccountRepository.findByUserIdAndIsActive(userId, true)).thenReturn(List.of(existingCard));
        when(plaidClient.itemRemove(any())).thenReturn(ok(new com.plaid.client.model.ItemRemoveResponse()));

        assertThatThrownBy(() -> plaidService.exchangePublicToken(userId, "public-token"))
                .isInstanceOf(com.financecoach.backend.exception.ValidationException.class)
                .hasMessage("These accounts are already connected.");

        verify(plaidClient).itemRemove(any());
        verify(plaidItemRepository).delete(saved);
        verify(bankAccountRepository, never()).save(any());
    }

    // --- the same card linked twice ---

    @Test
    void findDuplicateAccounts_returnsTheNewerCopyOfACardLinkedTwice() {
        UUID oldLogin = UUID.randomUUID();
        UUID newLogin = UUID.randomUUID();
        BankAccount original = card(oldLogin, "1234", LocalDateTime.now().minusDays(1));
        BankAccount copy = card(newLogin, "1234", LocalDateTime.now());
        BankAccount otherCard = card(newLogin, "9876", LocalDateTime.now());
        BankAccount noDigitsYet = card(newLogin, null, LocalDateTime.now());
        BankAccount checkingSameDigits = card(newLogin, "1234", LocalDateTime.now());
        checkingSameDigits.setAccountType("depository");
        checkingSameDigits.setAccountSubtype("checking");
        when(bankAccountRepository.findByUserIdAndIsActive(userId, true))
                .thenReturn(List.of(copy, otherCard, noDigitsYet, checkingSameDigits, original));

        assertThat(plaidService.findDuplicateAccounts(userId)).containsExactly(copy);
    }

    @Test
    void findDuplicateAccounts_twoAccountsOfTheSameLogin_areNotDuplicates() {
        UUID login = UUID.randomUUID();
        when(bankAccountRepository.findByUserIdAndIsActive(userId, true))
                .thenReturn(List.of(card(login, "1234", LocalDateTime.now().minusDays(1)), card(login, "1234", LocalDateTime.now())));

        assertThat(plaidService.findDuplicateAccounts(userId)).isEmpty();
    }

    @Test
    void retireDuplicateAccount_deactivatesIt_andRevokesItsLoginWhenNothingElseUsesIt() throws Exception {
        PlaidItem login = item(userId);
        BankAccount copy = card(login.getId(), "1234", LocalDateTime.now());
        when(bankAccountRepository.findByItemIdAndIsActive(login.getId(), true)).thenReturn(List.of());
        when(plaidItemRepository.findById(login.getId())).thenReturn(Optional.of(login));
        when(plaidClient.itemRemove(any())).thenReturn(ok(new com.plaid.client.model.ItemRemoveResponse()));

        plaidService.retireDuplicateAccount(copy);

        assertThat(copy.getIsActive()).isFalse();
        verify(bankAccountRepository).save(copy);
        verify(plaidItemRepository).delete(login);
    }

    @Test
    void exchangePublicToken_whenTheExchangeOrAccountLookupFails_throws() throws Exception {
        when(plaidClient.itemPublicTokenExchange(any())).thenReturn(error(400, "INVALID_PUBLIC_TOKEN"));
        assertThatThrownBy(() -> plaidService.exchangePublicToken(userId, "bad"))
                .isInstanceOf(PlaidIntegrationException.class).hasMessageContaining("INVALID_PUBLIC_TOKEN");

        stubExchange("access-1", "item-1");
        when(plaidClient.accountsGet(any())).thenReturn(error(500, "INTERNAL_SERVER_ERROR"));
        assertThatThrownBy(() -> plaidService.exchangePublicToken(userId, "public-token"))
                .isInstanceOf(PlaidIntegrationException.class).hasMessageContaining("Failed to get accounts");

        when(plaidClient.itemPublicTokenExchange(any())).thenReturn(networkFailure());
        assertThatThrownBy(() -> plaidService.exchangePublicToken(userId, "public-token"))
                .isInstanceOf(PlaidIntegrationException.class).hasMessageContaining("Network");

        verify(bankAccountRepository, never()).save(any());
    }

    // --- items ---

    @Test
    void getItem_returnsTheLinkedItem_orFailsIfItsMissing() {
        PlaidItem item = item(userId);
        BankAccount linked = account(userId, item.getId());
        BankAccount dangling = account(userId, UUID.randomUUID());
        when(plaidItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(plaidItemRepository.findById(dangling.getItemId())).thenReturn(Optional.empty());

        assertThat(plaidService.getItem(linked)).isSameAs(item);
        assertThatThrownBy(() -> plaidService.getItem(dangling)).isInstanceOf(PlaidIntegrationException.class);
    }

    @Test
    void getItem_forAnAccountLinkedBeforeItemsExisted_looksUpTheItemOnceAndAttachesIt() throws Exception {
        BankAccount legacy = account(userId, null);
        legacy.setPlaidAccessToken("legacy-token");
        when(plaidClient.itemGet(any()))
                .thenReturn(ok(new ItemGetResponse().item(new ItemWithConsentFields().itemId("item-legacy"))));
        when(plaidItemRepository.findByPlaidItemId("item-legacy")).thenReturn(Optional.empty());
        when(plaidItemRepository.save(any())).thenAnswer(inv -> {
            PlaidItem item = inv.getArgument(0);
            item.setId(UUID.randomUUID());
            return item;
        });

        PlaidItem item = plaidService.getItem(legacy);

        assertThat(item.getAccessToken()).isEqualTo("legacy-token");
        assertThat(item.getUserId()).isEqualTo(userId);
        assertThat(legacy.getItemId()).isEqualTo(item.getId());
        verify(bankAccountRepository).save(legacy);
    }

    @Test
    void getItem_backfill_whenPlaidFails_throws() throws Exception {
        BankAccount legacy = account(userId, null);
        when(plaidClient.itemGet(any())).thenReturn(error(400, "ITEM_LOGIN_REQUIRED")).thenReturn(networkFailure());

        assertThatThrownBy(() -> plaidService.getItem(legacy)).hasMessageContaining("ITEM_LOGIN_REQUIRED");
        assertThatThrownBy(() -> plaidService.getItem(legacy)).hasMessageContaining("Network");
    }

    @Test
    void getItems_returnsEachItemOnce_evenWithSeveralAccounts() {
        PlaidItem item = item(userId);
        when(plaidItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        List<PlaidItem> items = plaidService.getItems(List.of(account(userId, item.getId()), account(userId, item.getId())));

        assertThat(items).containsExactly(item);
    }

    @Test
    void getActiveAccountsByPlaidId_keysAccountsByTheirPlaidId() {
        PlaidItem item = item(userId);
        BankAccount account = account(userId, item.getId());
        account.setPlaidAccountId("plaid-acc");
        when(bankAccountRepository.findByItemIdAndIsActive(item.getId(), true)).thenReturn(List.of(account));

        Map<String, BankAccount> byPlaidId = plaidService.getActiveAccountsByPlaidId(item);

        assertThat(byPlaidId).containsOnlyKeys("plaid-acc");
    }

    // --- accounts ---

    @Test
    void getUserBankAccounts_returnsActiveAccounts() {
        List<BankAccount> accounts = List.of(account(userId, null));
        when(bankAccountRepository.findByUserIdAndIsActive(userId, true)).thenReturn(accounts);

        assertThat(plaidService.getUserBankAccounts(userId)).isEqualTo(accounts);
    }

    @Test
    void disconnectBankAccount_deactivatesTheOwnersAccount() {
        BankAccount account = account(userId, null);
        when(bankAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        plaidService.disconnectBankAccount(account.getId(), userId);

        assertThat(account.getIsActive()).isFalse();
        verify(bankAccountRepository).save(account);
    }

    @Test
    void disconnectBankAccount_lastAccountOfABank_revokesTheConnectionAtPlaid() throws Exception {
        PlaidItem item = item(userId);
        BankAccount last = account(userId, item.getId());
        when(bankAccountRepository.findById(last.getId())).thenReturn(Optional.of(last));
        when(bankAccountRepository.findByItemIdAndIsActive(item.getId(), true)).thenReturn(List.of());
        when(plaidItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(plaidClient.itemRemove(any())).thenReturn(ok(new com.plaid.client.model.ItemRemoveResponse()));

        plaidService.disconnectBankAccount(last.getId(), userId);

        verify(plaidClient).itemRemove(any());
        verify(plaidItemRepository).delete(item);
    }

    @Test
    void disconnectBankAccount_otherAccountsStillActive_keepsTheConnection() throws Exception {
        PlaidItem item = item(userId);
        BankAccount checking = account(userId, item.getId());
        when(bankAccountRepository.findById(checking.getId())).thenReturn(Optional.of(checking));
        when(bankAccountRepository.findByItemIdAndIsActive(item.getId(), true))
                .thenReturn(List.of(account(userId, item.getId())));

        plaidService.disconnectBankAccount(checking.getId(), userId);

        verify(plaidClient, never()).itemRemove(any());
    }

    @Test
    void disconnectBankAccount_whenPlaidCantRevoke_keepsTheItemForLater() throws Exception {
        PlaidItem item = item(userId);
        BankAccount last = account(userId, item.getId());
        when(bankAccountRepository.findById(last.getId())).thenReturn(Optional.of(last));
        when(bankAccountRepository.findByItemIdAndIsActive(item.getId(), true)).thenReturn(List.of());
        when(plaidItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(plaidClient.itemRemove(any())).thenReturn(error(500, "INTERNAL_SERVER_ERROR")).thenReturn(error(400, "ITEM_NOT_FOUND"));

        plaidService.disconnectBankAccount(last.getId(), userId);
        verify(plaidItemRepository, never()).delete(any(PlaidItem.class));

        // Already gone at Plaid counts as revoked
        plaidService.disconnectBankAccount(last.getId(), userId);
        verify(plaidItemRepository).delete(item);
    }

    @Test
    void disconnectBankAccount_ofAnotherUser_isRejected_andUnknown_isNotFound() {
        BankAccount theirs = account(UUID.randomUUID(), null);
        UUID missing = UUID.randomUUID();
        when(bankAccountRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(bankAccountRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> plaidService.disconnectBankAccount(theirs.getId(), userId))
                .isInstanceOf(UnauthorizedAccessException.class);
        assertThatThrownBy(() -> plaidService.disconnectBankAccount(missing, userId))
                .isInstanceOf(BankAccountNotFoundException.class);
        verify(bankAccountRepository, never()).save(any());
    }

    // --- investments ---

    @Test
    void getHoldings_returnsPlaidsResponse_andWrapsFailures() throws Exception {
        InvestmentsHoldingsGetResponse holdings = new InvestmentsHoldingsGetResponse();
        when(plaidClient.investmentsHoldingsGet(any()))
                .thenReturn(ok(holdings))
                .thenReturn(error(400, "PRODUCTS_NOT_SUPPORTED"))
                .thenReturn(networkFailure());

        assertThat(plaidService.getHoldings("token")).isSameAs(holdings);
        assertThatThrownBy(() -> plaidService.getHoldings("token")).hasMessageContaining("PRODUCTS_NOT_SUPPORTED");
        assertThatThrownBy(() -> plaidService.getHoldings("token")).hasMessageContaining("Unable to fetch");
    }

    @Test
    void getInvestmentTransactions_returnsPlaidsResponse_andWrapsFailures() throws Exception {
        InvestmentsTransactionsGetResponse transactions = new InvestmentsTransactionsGetResponse().totalInvestmentTransactions(3);
        when(plaidClient.investmentsTransactionsGet(any()))
                .thenReturn(ok(transactions))
                .thenReturn(error(400, "INVALID_REQUEST"))
                .thenReturn(networkFailure());
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);

        assertThat(plaidService.getInvestmentTransactions("token", start, end)).isSameAs(transactions);
        assertThatThrownBy(() -> plaidService.getInvestmentTransactions("token", start, end))
                .isInstanceOf(PlaidIntegrationException.class);
        assertThatThrownBy(() -> plaidService.getInvestmentTransactions("token", start, end))
                .hasMessageContaining("Unable to fetch");
    }

    // --- bank branding ---

    @Test
    void getItemsWithBranding_fetchesTheBanksLogoOnce_andReusesIt() throws Exception {
        PlaidItem chase = item(userId);
        chase.setInstitutionId("ins_3");
        BankAccount card = new BankAccount();
        card.setItemId(chase.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(chase));
        when(plaidClient.institutionsGetById(any())).thenReturn(ok(new InstitutionsGetByIdResponse()
                .institution(new Institution().name("Chase").logo("iVBORw0KGgo=").primaryColor("#117aca"))));

        Map<UUID, PlaidItem> first = plaidService.getItemsWithBranding(List.of(card, card));
        plaidService.getItemsWithBranding(List.of(card));

        ArgumentCaptor<com.plaid.client.model.InstitutionsGetByIdRequest> request =
                ArgumentCaptor.forClass(com.plaid.client.model.InstitutionsGetByIdRequest.class);
        verify(plaidClient, times(1)).institutionsGetById(request.capture());
        assertThat(request.getValue().getOptions().getIncludeOptionalMetadata()).isTrue();
        assertThat(first.get(chase.getId()).getInstitutionLogo()).isEqualTo("iVBORw0KGgo=");
        assertThat(first.get(chase.getId()).getInstitutionColor()).isEqualTo("#117aca");
        verify(plaidItemRepository).save(chase);
    }

    @Test
    void getItemsWithBranding_bankWithoutALogo_isNotAskedAgain() throws Exception {
        PlaidItem smallBank = item(userId);
        smallBank.setInstitutionId("ins_999");
        BankAccount account = new BankAccount();
        account.setItemId(smallBank.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(smallBank));
        when(plaidClient.institutionsGetById(any())).thenReturn(ok(new InstitutionsGetByIdResponse()
                .institution(new Institution().name("Small Credit Union"))));

        plaidService.getItemsWithBranding(List.of(account));
        plaidService.getItemsWithBranding(List.of(account));

        verify(plaidClient, times(1)).institutionsGetById(any());
        assertThat(smallBank.getInstitutionLogo()).isNull();
        assertThat(smallBank.getInstitutionBrandingFetched()).isTrue();
    }

    @Test
    void getItemsWithBranding_bankWithoutAPlaidLogo_usesItsSiteIcon() throws Exception {
        PlaidItem chase = item(userId);
        chase.setInstitutionId("ins_56");
        BankAccount card = new BankAccount();
        card.setItemId(chase.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(chase));
        when(plaidClient.institutionsGetById(any())).thenReturn(ok(new InstitutionsGetByIdResponse()
                .institution(new Institution().name("Chase").primaryColor("#095aa6").url("https://www.chase.com"))));
        when(iconFetcher.fetchIcon("https://www.chase.com")).thenReturn("data:image/jpeg;base64,/9j/4A==");

        plaidService.getItemsWithBranding(List.of(card));
        plaidService.getItemsWithBranding(List.of(card));

        verify(plaidClient, times(1)).institutionsGetById(any());
        verify(iconFetcher, times(1)).fetchIcon(any());
        assertThat(chase.getInstitutionLogo()).isEqualTo("data:image/jpeg;base64,/9j/4A==");
        assertThat(chase.getInstitutionColor()).isEqualTo("#095aa6");
        assertThat(chase.getInstitutionIconFetched()).isTrue();
    }

    @Test
    void getItemsWithBranding_bankBrandedBeforeSiteIcons_isLookedUpOnceMore() throws Exception {
        PlaidItem chase = item(userId);
        chase.setInstitutionId("ins_56");
        chase.setInstitutionColor("#095aa6");
        chase.setInstitutionBrandingFetched(true);
        BankAccount card = new BankAccount();
        card.setItemId(chase.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(chase));
        when(plaidClient.institutionsGetById(any())).thenReturn(ok(new InstitutionsGetByIdResponse()
                .institution(new Institution().name("Chase").primaryColor("#095aa6").url("https://www.chase.com"))));
        when(iconFetcher.fetchIcon(any())).thenReturn(null); // the site has no icon either

        plaidService.getItemsWithBranding(List.of(card));
        plaidService.getItemsWithBranding(List.of(card));

        verify(plaidClient, times(1)).institutionsGetById(any());
        assertThat(chase.getInstitutionLogo()).isNull();
        assertThat(chase.getInstitutionIconFetched()).isTrue();
    }

    @Test
    void getItemsWithBranding_whenTheIconLookupFails_waitsAndTriesAgain() throws Exception {
        PlaidItem chase = item(userId);
        chase.setInstitutionId("ins_56");
        BankAccount card = new BankAccount();
        card.setItemId(chase.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(chase));
        when(plaidClient.institutionsGetById(any())).thenReturn(ok(new InstitutionsGetByIdResponse()
                .institution(new Institution().name("Chase").url("https://www.chase.com"))));
        when(iconFetcher.fetchIcon(any())).thenThrow(new java.io.IOException("Icon lookup returned HTTP 503"));

        plaidService.getItemsWithBranding(List.of(card));
        plaidService.getItemsWithBranding(List.of(card));

        verify(iconFetcher, times(1)).fetchIcon(any());
        assertThat(chase.getInstitutionBrandingFetched()).isNotEqualTo(Boolean.TRUE);
        verify(plaidItemRepository, never()).save(any());
    }

    @Test
    void getItemsWithBranding_afterATemporaryFailure_waitsBeforeAskingAgain() throws Exception {
        PlaidItem chase = item(userId);
        chase.setInstitutionId("ins_3");
        BankAccount card = new BankAccount();
        card.setItemId(chase.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(chase));
        when(plaidClient.institutionsGetById(any())).thenReturn(error(500, "INTERNAL_SERVER_ERROR"));

        plaidService.getItemsWithBranding(List.of(card));
        plaidService.getItemsWithBranding(List.of(card));

        verify(plaidClient, times(1)).institutionsGetById(any());
    }

    @Test
    void getItemsWithBranding_unknownInstitution_isNotAskedAgain() throws Exception {
        PlaidItem gone = item(userId);
        gone.setInstitutionId("ins_gone");
        BankAccount account = new BankAccount();
        account.setItemId(gone.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(gone));
        when(plaidClient.institutionsGetById(any())).thenReturn(error(400, "INVALID_INSTITUTION"));

        plaidService.getItemsWithBranding(List.of(account));
        plaidService.getItemsWithBranding(List.of(account));

        verify(plaidClient, times(1)).institutionsGetById(any());
        assertThat(gone.getInstitutionBrandingFetched()).isTrue();
    }

    @Test
    void getItemsWithBranding_whenPlaidFails_returnsTheItemAndRetriesNextTime() throws Exception {
        PlaidItem chase = item(userId);
        chase.setInstitutionId("ins_3");
        BankAccount card = new BankAccount();
        card.setItemId(chase.getId());
        when(plaidItemRepository.findAllById(any())).thenReturn(List.of(chase));
        when(plaidClient.institutionsGetById(any()))
                .thenReturn(error(500, "INTERNAL_SERVER_ERROR"))
                .thenReturn(networkFailure());
        plaidService.brandingRetryAfterMillis = 0; // the retry is due straight away

        assertThat(plaidService.getItemsWithBranding(List.of(card))).containsKey(chase.getId());
        plaidService.getItemsWithBranding(List.of(card));

        verify(plaidClient, times(2)).institutionsGetById(any());
        assertThat(chase.getInstitutionBrandingFetched()).isNotEqualTo(Boolean.TRUE);
        verify(plaidItemRepository, never()).save(any());
    }

    // --- helpers ---

    private void stubExchange(String accessToken, String itemId) throws Exception {
        when(plaidClient.itemPublicTokenExchange(any()))
                .thenReturn(ok(new ItemPublicTokenExchangeResponse().accessToken(accessToken).itemId(itemId)));
    }

    private AccountBase plaidAccount(String id, AccountType type, AccountSubtype subtype, Double current, Double available) {
        AccountBase account = new AccountBase().accountId(id).name("Account " + id).type(type).subtype(subtype);
        if (current != null || available != null) {
            account.balances(new AccountBalance().current(current).available(available).isoCurrencyCode("USD"));
        }
        return account;
    }

    private PlaidItem item(UUID owner) {
        PlaidItem item = new PlaidItem();
        item.setId(UUID.randomUUID());
        item.setUserId(owner);
        item.setAccessToken("access");
        return item;
    }

    private BankAccount card(UUID itemId, String mask, LocalDateTime createdAt) {
        BankAccount card = account(userId, itemId);
        card.setInstitutionId("ins_56");
        card.setAccountType("credit");
        card.setAccountSubtype("credit card");
        card.setMask(mask);
        card.setCreatedAt(createdAt);
        return card;
    }

    private BankAccount account(UUID owner, UUID itemId) {
        BankAccount account = new BankAccount();
        account.setId(UUID.randomUUID());
        account.setUserId(owner);
        account.setItemId(itemId);
        return account;
    }

    // Fake retrofit calls built with a default answer, so they can be created inline inside
    // another when(...) without nesting Mockito stubbing
    @SuppressWarnings("unchecked")
    private static <T> Call<T> call(Response<T> response, IOException failure) {
        return mock(Call.class, invocation -> {
            if (!invocation.getMethod().getName().equals("execute")) {
                return null;
            }
            if (failure != null) {
                throw failure;
            }
            return response;
        });
    }

    private static <T> Call<T> ok(T body) {
        return call(Response.success(body), null);
    }

    private static <T> Call<T> error(int status, String errorCode) {
        return call(Response.error(status, ResponseBody.create(
                "{\"error_code\": \"" + errorCode + "\"}", MediaType.get("application/json"))), null);
    }

    private static <T> Call<T> networkFailure() {
        return call(null, new IOException("connection reset"));
    }
}

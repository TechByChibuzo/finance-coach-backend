package com.financecoach.backend.service;

import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.PlaidItemRepository;
import com.financecoach.backend.repository.UserRepository;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;
import retrofit2.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidServiceTest {

    @Mock private PlaidApi plaidClient;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private PlaidItemRepository plaidItemRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private PlaidService plaidService;
    private PlaidItem item;
    private BankAccount checking;
    private BankAccount credit;

    @BeforeEach
    void setUp() {
        plaidService = new PlaidService(plaidClient, bankAccountRepository, plaidItemRepository, subscriptionService,
                userRepository, new TransactionTemplate(transactionManager));

        item = new PlaidItem();
        item.setId(UUID.randomUUID());
        item.setAccessToken("access-item");

        checking = account("plaid-checking", "100.00");
        credit = account("plaid-credit", "250.00");
    }

    @Test
    void refreshBalances_updatesEveryAccountInTheItem() throws Exception {
        when(bankAccountRepository.findByItemIdAndIsActive(item.getId(), true))
                .thenReturn(List.of(checking, credit));
        stubAccountsGet(Response.success(new AccountsGetResponse().accounts(List.of(
                plaidAccount("plaid-checking", 1520.75, 1500.00),
                plaidAccount("plaid-credit", 310.40, null)))));

        plaidService.refreshBalances(item);

        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("1520.75");
        assertThat(checking.getAvailableBalance()).isEqualByComparingTo("1500.00");
        assertThat(credit.getCurrentBalance()).isEqualByComparingTo("310.40");
        assertThat(credit.getAvailableBalance()).isNull();
        verify(bankAccountRepository).save(checking);
        verify(bankAccountRepository).save(credit);
    }

    @Test
    void refreshBalances_whenPlaidFails_throwsAndLeavesBalancesUnchanged() throws Exception {
        stubAccountsGet(Response.error(400, ResponseBody.create(
                "{\"error_code\": \"ITEM_LOGIN_REQUIRED\"}", MediaType.get("application/json"))));

        assertThatThrownBy(() -> plaidService.refreshBalances(item))
                .isInstanceOf(PlaidIntegrationException.class);
        verify(bankAccountRepository, never()).save(any());
    }

    @Test
    void applyBalances_ignoresAccountsWeDontTrack() {
        when(bankAccountRepository.findByItemIdAndIsActive(item.getId(), true)).thenReturn(List.of(checking));

        plaidService.applyBalances(item, List.of(
                plaidAccount("plaid-checking", 99.99, 99.99),
                plaidAccount("plaid-unlinked", 5000.00, 5000.00)));

        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("99.99");
        verify(bankAccountRepository).save(checking);
        verify(bankAccountRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void applyBalances_whenPlaidOmitsABalance_keepsTheStoredValue() {
        checking.setAvailableBalance(new BigDecimal("80.00"));
        when(bankAccountRepository.findByItemIdAndIsActive(item.getId(), true)).thenReturn(List.of(checking));

        plaidService.applyBalances(item, List.of(plaidAccount("plaid-checking", null, null)));

        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("100.00");
        assertThat(checking.getAvailableBalance()).isEqualByComparingTo("80.00");
    }

    @Test
    void getItem_belongingToAnotherUser_isRejected() {
        item.setUserId(UUID.randomUUID());
        checking.setUserId(UUID.randomUUID());
        when(plaidItemRepository.findById(item.getId())).thenReturn(java.util.Optional.of(item));

        assertThatThrownBy(() -> plaidService.getItem(checking))
                .isInstanceOf(com.financecoach.backend.exception.UnauthorizedAccessException.class);
    }

    @Test
    void removeAllItemsForUser_revokesItemsAndOlderAccountsWithoutAnItem() throws Exception {
        UUID userId = UUID.randomUUID();
        item.setUserId(userId);
        BankAccount legacy = account("plaid-legacy", "0.00");
        legacy.setItemId(null);
        legacy.setPlaidAccessToken("access-legacy");
        when(plaidItemRepository.findByUserId(userId)).thenReturn(List.of(item));
        when(bankAccountRepository.findByUserId(userId)).thenReturn(List.of(checking, legacy));

        @SuppressWarnings("unchecked")
        Call<com.plaid.client.model.ItemRemoveResponse> ok = mock(Call.class);
        when(ok.execute()).thenReturn(Response.success(new com.plaid.client.model.ItemRemoveResponse()));
        @SuppressWarnings("unchecked")
        Call<com.plaid.client.model.ItemRemoveResponse> failed = mock(Call.class);
        when(failed.execute()).thenReturn(Response.error(400, ResponseBody.create(
                "{\"error_code\": \"ITEM_NOT_FOUND\"}", MediaType.get("application/json"))));
        when(plaidClient.itemRemove(any())).thenReturn(ok, failed);

        int removed = plaidService.removeAllItemsForUser(userId);

        // Both tokens are attempted; an item Plaid no longer knows about counts as revoked
        org.mockito.ArgumentCaptor<com.plaid.client.model.ItemRemoveRequest> requests =
                org.mockito.ArgumentCaptor.forClass(com.plaid.client.model.ItemRemoveRequest.class);
        verify(plaidClient, org.mockito.Mockito.times(2)).itemRemove(requests.capture());
        assertThat(requests.getAllValues()).extracting(com.plaid.client.model.ItemRemoveRequest::getAccessToken)
                .containsExactly("access-item", "access-legacy");
        assertThat(removed).isEqualTo(2);
    }

    @Test
    void removeAllItemsForUser_whenPlaidIsUnavailable_throwsSoDeletionCanBeRetried() throws Exception {
        UUID userId = UUID.randomUUID();
        item.setUserId(userId);
        when(plaidItemRepository.findByUserId(userId)).thenReturn(List.of(item));
        when(bankAccountRepository.findByUserId(userId)).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Call<com.plaid.client.model.ItemRemoveResponse> outage = mock(Call.class);
        when(outage.execute()).thenReturn(Response.error(500, ResponseBody.create(
                "{\"error_code\": \"INTERNAL_SERVER_ERROR\"}", MediaType.get("application/json"))));
        when(plaidClient.itemRemove(any())).thenReturn(outage);

        assertThatThrownBy(() -> plaidService.removeAllItemsForUser(userId))
                .isInstanceOf(PlaidIntegrationException.class);
    }

    private BankAccount account(String plaidAccountId, String balance) {
        BankAccount account = new BankAccount();
        account.setId(UUID.randomUUID());
        account.setItemId(item.getId());
        account.setPlaidAccountId(plaidAccountId);
        account.setCurrentBalance(new BigDecimal(balance));
        return account;
    }

    private AccountBase plaidAccount(String plaidAccountId, Double current, Double available) {
        return new AccountBase()
                .accountId(plaidAccountId)
                .balances(new AccountBalance().current(current).available(available).isoCurrencyCode("USD"));
    }

    @SuppressWarnings("unchecked")
    private void stubAccountsGet(Response<AccountsGetResponse> response) throws Exception {
        Call<AccountsGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(response);
        when(plaidClient.accountsGet(any(AccountsGetRequest.class))).thenReturn(call);
    }
}

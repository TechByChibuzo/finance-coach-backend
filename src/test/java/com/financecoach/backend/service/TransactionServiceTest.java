package com.financecoach.backend.service;

import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.TransactionRepository;
import com.plaid.client.model.TransactionsGetRequest;
import com.plaid.client.model.TransactionsGetResponse;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private PlaidApi plaidClient;
    @Mock private TransactionRepository transactionRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private VectorStoreService vectorStoreService;
    @Mock private PlaidService plaidService;
    @Mock private MetricsService metricsService;

    private TransactionService transactionService;

    private UUID userId;
    private PlaidItem item;
    private BankAccount checking;
    private BankAccount savings;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(
                plaidClient, transactionRepository, bankAccountRepository, vectorStoreService, plaidService);
        ReflectionTestUtils.setField(transactionService, "metricsService", metricsService);

        userId = UUID.randomUUID();
        item = new PlaidItem();
        item.setId(UUID.randomUUID());
        item.setAccessToken("access-item");

        checking = account("plaid-checking");
        savings = account("plaid-savings");
    }

    @Test
    void syncItem_assignsEachTransactionToItsOwnAccount() throws Exception {
        stubItemAccounts(checking, savings);
        stubPlaidTransactions(
                plaidTx("tx-1", "plaid-checking"),
                plaidTx("tx-2", "plaid-savings"),
                plaidTx("tx-3", "plaid-checking"));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Transaction> saved = transactionService.syncItem(item);

        assertThat(saved).extracting(Transaction::getPlaidTransactionId, Transaction::getAccountId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("tx-1", checking.getId()),
                        org.assertj.core.groups.Tuple.tuple("tx-2", savings.getId()),
                        org.assertj.core.groups.Tuple.tuple("tx-3", checking.getId()));
    }

    @Test
    void syncItem_callsPlaidOncePerItem_notPerAccount() throws Exception {
        stubItemAccounts(checking, savings);
        stubPlaidTransactions();

        transactionService.syncItem(item);

        verify(plaidClient, times(1)).transactionsGet(any());
    }

    @Test
    void syncItem_skipsTransactionsForUnlinkedAccounts() throws Exception {
        stubItemAccounts(checking);
        stubPlaidTransactions(plaidTx("tx-1", "plaid-checking"), plaidTx("tx-2", "plaid-disconnected"));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Transaction> saved = transactionService.syncItem(item);

        assertThat(saved).extracting(Transaction::getPlaidTransactionId).containsExactly("tx-1");
    }

    @Test
    void syncTransactions_returnsOnlyTheRequestedAccountsTransactions() throws Exception {
        when(bankAccountRepository.findById(savings.getId())).thenReturn(Optional.of(savings));
        when(plaidService.getItem(savings)).thenReturn(item);
        stubItemAccounts(checking, savings);
        stubPlaidTransactions(plaidTx("tx-1", "plaid-checking"), plaidTx("tx-2", "plaid-savings"));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Transaction> result = transactionService.syncTransactions(savings.getId(), userId);

        assertThat(result).extracting(Transaction::getPlaidTransactionId).containsExactly("tx-2");
    }

    @Test
    void syncTransactions_forAnotherUsersAccount_isRejected() throws Exception {
        when(bankAccountRepository.findById(savings.getId())).thenReturn(Optional.of(savings));

        assertThatThrownBy(() -> transactionService.syncTransactions(savings.getId(), UUID.randomUUID()))
                .isInstanceOf(UnauthorizedAccessException.class);
        verify(plaidClient, never()).transactionsGet(any());
    }

    // --- helpers ---

    private BankAccount account(String plaidAccountId) {
        BankAccount account = new BankAccount();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        account.setItemId(item.getId());
        account.setPlaidAccountId(plaidAccountId);
        return account;
    }

    private void stubItemAccounts(BankAccount... accounts) {
        Map<String, BankAccount> byPlaidId = new LinkedHashMap<>();
        for (BankAccount account : accounts) {
            byPlaidId.put(account.getPlaidAccountId(), account);
        }
        when(plaidService.getActiveAccountsByPlaidId(item)).thenReturn(byPlaidId);
    }

    @SuppressWarnings("unchecked")
    private void stubPlaidTransactions(com.plaid.client.model.Transaction... transactions) throws Exception {
        Call<TransactionsGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(
                new TransactionsGetResponse().transactions(List.of(transactions))));
        when(plaidClient.transactionsGet(any(TransactionsGetRequest.class))).thenReturn(call);
    }

    private com.plaid.client.model.Transaction plaidTx(String transactionId, String plaidAccountId) {
        return new com.plaid.client.model.Transaction()
                .transactionId(transactionId)
                .accountId(plaidAccountId)
                .amount(12.50)
                .date(LocalDate.now())
                .name("Coffee")
                .pending(false)
                .isoCurrencyCode("USD");
    }
}

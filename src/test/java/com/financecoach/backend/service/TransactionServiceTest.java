package com.financecoach.backend.service;

import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.BankAccountRepository;
import com.financecoach.backend.repository.PlaidItemRepository;
import com.financecoach.backend.repository.TransactionRepository;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.mockito.ArgumentCaptor;
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
    @Mock private PlaidItemRepository plaidItemRepository;

    private TransactionService transactionService;

    private UUID userId;
    private PlaidItem item;
    private BankAccount checking;
    private BankAccount savings;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(
                plaidClient, transactionRepository, bankAccountRepository, vectorStoreService, plaidService,
                plaidItemRepository);
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
    void syncItem_indexesNewTransactionsInTheBackground_inOneCall() throws Exception {
        stubItemAccounts(checking, savings);
        stubPlaidTransactions(plaidTx("tx-1", "plaid-checking"), plaidTx("tx-2", "plaid-savings"));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Transaction> saved = transactionService.syncItem(item);

        verify(vectorStoreService).indexTransactionsAsync(saved);
        verify(vectorStoreService, never()).indexTransaction(any());
    }

    @Test
    void syncItem_doesNotReindexATransactionModifiedAndRemovedInTheSameSync() throws Exception {
        Transaction stored = existing("tx-1");
        stubItemAccounts(checking);
        stubSyncPages(page(false, "cursor-1",
                List.of(plaidTx("tx-2", "plaid-checking")),
                List.of(plaidTx("tx-1", "plaid-checking")),
                List.of(new RemovedTransaction().transactionId("tx-1"))));
        when(transactionRepository.findByPlaidTransactionId("tx-1")).thenReturn(java.util.Optional.of(stored));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.syncItem(item);

        ArgumentCaptor<List<Transaction>> indexed = ArgumentCaptor.forClass(List.class);
        verify(vectorStoreService).indexTransactionsAsync(indexed.capture());
        assertThat(indexed.getValue()).extracting(Transaction::getPlaidTransactionId).containsExactly("tx-2");
    }

    @Test
    void syncItem_callsPlaidOncePerItem_notPerAccount() throws Exception {
        stubItemAccounts(checking, savings);
        stubPlaidTransactions();

        transactionService.syncItem(item);

        verify(plaidClient, times(1)).transactionsSync(any());
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
    void syncItem_pagesThroughAllResults_andStoresTheFinalCursor() throws Exception {
        stubItemAccounts(checking);
        stubSyncPages(
                page(true, "cursor-1", List.of(plaidTx("tx-1", "plaid-checking")), List.of(), List.of()),
                page(false, "cursor-2", List.of(plaidTx("tx-2", "plaid-checking")), List.of(), List.of()));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Transaction> saved = transactionService.syncItem(item);

        assertThat(saved).extracting(Transaction::getPlaidTransactionId).containsExactly("tx-1", "tx-2");
        assertThat(item.getTransactionsCursor()).isEqualTo("cursor-2");
        verify(plaidItemRepository).save(item);

        ArgumentCaptor<TransactionsSyncRequest> requests = ArgumentCaptor.forClass(TransactionsSyncRequest.class);
        verify(plaidClient, times(2)).transactionsSync(requests.capture());
        assertThat(requests.getAllValues().get(0).getCursor()).isNull();
        assertThat(requests.getAllValues().get(1).getCursor()).isEqualTo("cursor-1");
    }

    @Test
    void syncItem_resumesFromTheStoredCursor() throws Exception {
        item.setTransactionsCursor("cursor-saved");
        stubItemAccounts(checking);
        stubPlaidTransactions();

        transactionService.syncItem(item);

        ArgumentCaptor<TransactionsSyncRequest> request = ArgumentCaptor.forClass(TransactionsSyncRequest.class);
        verify(plaidClient).transactionsSync(request.capture());
        assertThat(request.getValue().getCursor()).isEqualTo("cursor-saved");
    }

    @Test
    void syncItem_removesDeletedTransactions_suchAsPendingReplacedByPosted() throws Exception {
        Transaction pending = existing("tx-pending");
        stubItemAccounts(checking);
        stubSyncPages(page(false, "cursor-1",
                List.of(plaidTx("tx-posted", "plaid-checking")),
                List.of(),
                List.of(new RemovedTransaction().transactionId("tx-pending").accountId("plaid-checking"))));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findByPlaidTransactionId("tx-pending")).thenReturn(Optional.of(pending));

        transactionService.syncItem(item);

        verify(transactionRepository).delete(pending);
        verify(vectorStoreService).removeTransaction("tx-pending");
    }

    @Test
    void syncItem_updatesModifiedTransactions_andReindexesThem() throws Exception {
        Transaction stored = existing("tx-1");
        stored.setAmount(new java.math.BigDecimal("10.00"));
        stubItemAccounts(checking);
        stubSyncPages(page(false, "cursor-1", List.of(),
                List.of(plaidTx("tx-1", "plaid-checking").amount(42.00)), List.of()));
        when(transactionRepository.findByPlaidTransactionId("tx-1")).thenReturn(Optional.of(stored));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.syncItem(item);

        assertThat(stored.getAmount()).isEqualByComparingTo("42.00");
        verify(vectorStoreService).removeTransaction("tx-1");
        verify(vectorStoreService).indexTransactionsAsync(List.of(stored));
    }

    @Test
    void syncItem_restartsWhenDataChangesDuringPagination() throws Exception {
        stubItemAccounts(checking);
        stubSyncResponses(
                Response.success(page(true, "cursor-1", List.of(plaidTx("tx-stale", "plaid-checking")),
                        List.of(), List.of())),
                Response.error(400, ResponseBody.create(
                        "{\"error_code\": \"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION\"}",
                        MediaType.get("application/json"))),
                Response.success(page(false, "cursor-fresh", List.of(plaidTx("tx-fresh", "plaid-checking")),
                        List.of(), List.of())));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Transaction> saved = transactionService.syncItem(item);

        // The partial first pass is discarded; only the restarted pass is applied
        assertThat(saved).extracting(Transaction::getPlaidTransactionId).containsExactly("tx-fresh");
        assertThat(item.getTransactionsCursor()).isEqualTo("cursor-fresh");
    }

    @Test
    void syncItem_refreshesBalancesAfterSyncing() throws Exception {
        stubItemAccounts(checking);
        stubPlaidTransactions();

        transactionService.syncItem(item);

        verify(plaidService).refreshBalances(item);
    }

    @Test
    void syncItem_whenBalanceRefreshFails_stillSavesTransactions() throws Exception {
        stubItemAccounts(checking);
        stubPlaidTransactions(plaidTx("tx-1", "plaid-checking"));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new com.financecoach.backend.exception.PlaidIntegrationException("down"))
                .when(plaidService).refreshBalances(item);

        List<Transaction> saved = transactionService.syncItem(item);

        assertThat(saved).hasSize(1);
        assertThat(item.getTransactionsCursor()).isEqualTo("cursor-1");
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
        verify(plaidClient, never()).transactionsSync(any());
    }

    @Test
    void syncItem_mapsPlaidFieldsOntoTheTransaction() throws Exception {
        stubItemAccounts(checking);
        stubPlaidTransactions(plaidTx("tx-1", "plaid-checking")
                .merchantName("Blue Bottle")
                .personalFinanceCategory(new com.plaid.client.model.PersonalFinanceCategory()
                        .primary("FOOD_AND_DRINK").detailed("FOOD_AND_DRINK_COFFEE"))
                .paymentChannel(com.plaid.client.model.Transaction.PaymentChannelEnum.IN_STORE)
                .location(new com.plaid.client.model.Location().address("1 Main St").city("Oakland").region("CA")));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction saved = transactionService.syncItem(item).get(0);

        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAmount()).isEqualByComparingTo("12.50");
        assertThat(saved.getMerchantName()).isEqualTo("Blue Bottle");
        assertThat(saved.getCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(saved.getSubcategory()).isEqualTo("FOOD_AND_DRINK_COFFEE");
        assertThat(saved.getPaymentChannel()).isEqualTo("in store");
        assertThat(saved.getLocationCity()).isEqualTo("Oakland");
        assertThat(saved.getLocationRegion()).isEqualTo("CA");
        assertThat(saved.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void syncItem_fallsBackToLegacyPlaidCategories() throws Exception {
        stubItemAccounts(checking);
        stubPlaidTransactions(
                plaidTx("tx-1", "plaid-checking").category(List.of("Shops", "Supermarkets")),
                plaidTx("tx-2", "plaid-checking").category(List.of("Transfer")));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Transaction> saved = transactionService.syncItem(item);

        assertThat(saved.get(0).getCategory()).isEqualTo("Shops");
        assertThat(saved.get(0).getSubcategory()).isEqualTo("Supermarkets");
        assertThat(saved.get(1).getCategory()).isEqualTo("Transfer");
        assertThat(saved.get(1).getSubcategory()).isNull();
    }

    @Test
    void syncItem_skipsTransactionsAlreadyStored_andChangesForUnlinkedAccounts() throws Exception {
        stubItemAccounts(checking);
        stubSyncPages(page(false, "cursor-1",
                List.of(plaidTx("tx-dupe", "plaid-checking")),
                List.of(plaidTx("tx-elsewhere", "plaid-unlinked")),
                List.of(new RemovedTransaction().transactionId("tx-never-stored"))));
        when(transactionRepository.existsByPlaidTransactionId("tx-dupe")).thenReturn(true);
        when(transactionRepository.findByPlaidTransactionId("tx-never-stored")).thenReturn(java.util.Optional.empty());

        assertThat(transactionService.syncItem(item)).isEmpty();
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void syncItem_forAnItemWithNoActiveAccounts_doesNotCallPlaid() throws Exception {
        when(plaidService.getActiveAccountsByPlaidId(item)).thenReturn(Map.of());

        assertThat(transactionService.syncItem(item)).isEmpty();
        verify(plaidClient, never()).transactionsSync(any());
    }

    @Test
    void syncItem_whenPlaidFailsOrIsUnreachable_throws() throws Exception {
        stubItemAccounts(checking);
        stubSyncResponses(Response.error(400, ResponseBody.create(
                "{\"error_code\": \"ITEM_LOGIN_REQUIRED\"}", MediaType.get("application/json"))));

        assertThatThrownBy(() -> transactionService.syncItem(item))
                .isInstanceOf(com.financecoach.backend.exception.PlaidIntegrationException.class)
                .hasMessageContaining("ITEM_LOGIN_REQUIRED");

        @SuppressWarnings("unchecked")
        Call<TransactionsSyncResponse> broken = mock(Call.class);
        when(broken.execute()).thenThrow(new java.io.IOException("reset"));
        when(plaidClient.transactionsSync(any(TransactionsSyncRequest.class))).thenReturn(broken);
        assertThatThrownBy(() -> transactionService.syncItem(item)).hasMessageContaining("Network error");
    }

    @Test
    void syncItem_whenDataKeepsChangingDuringPagination_givesUpAfterThreeTries() throws Exception {
        stubItemAccounts(checking);
        // A fresh response per attempt: an error body can only be read once
        stubSyncResponses(mutationError(), mutationError(), mutationError());

        assertThatThrownBy(() -> transactionService.syncItem(item)).hasMessageContaining("kept changing");
        assertThat(item.getTransactionsCursor()).isNull();
    }

    @Test
    void syncAllTransactions_syncsEveryItem_andRequiresALinkedAccount() throws Exception {
        UUID lonelyUser = UUID.randomUUID();
        when(bankAccountRepository.findByUserIdAndIsActive(lonelyUser, true)).thenReturn(List.of());
        when(bankAccountRepository.findByUserIdAndIsActive(userId, true)).thenReturn(List.of(checking, savings));
        when(plaidService.getItems(List.of(checking, savings))).thenReturn(List.of(item));
        stubItemAccounts(checking, savings);
        stubPlaidTransactions(plaidTx("tx-1", "plaid-savings"));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(transactionService.syncAllTransactions(userId)).hasSize(1);
        assertThatThrownBy(() -> transactionService.syncAllTransactions(lonelyUser))
                .isInstanceOf(com.financecoach.backend.exception.BankAccountNotFoundException.class);
    }

    @Test
    void syncTransactions_forAnUnknownAccount_throwsNotFound() {
        UUID missing = UUID.randomUUID();
        when(bankAccountRepository.findById(missing)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> transactionService.syncTransactions(missing, userId))
                .isInstanceOf(com.financecoach.backend.exception.BankAccountNotFoundException.class);
    }

    @Test
    void queries_filterByDateRangeOrCategory() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        List<Transaction> inRange = List.of(new Transaction());
        List<Transaction> all = List.of(new Transaction(), new Transaction());
        when(transactionRepository.findByUserIdAndDateBetween(userId, start, end)).thenReturn(inRange);
        when(transactionRepository.findByUserId(userId)).thenReturn(all);
        when(transactionRepository.findByUserIdAndCategory(userId, "TRAVEL")).thenReturn(inRange);

        assertThat(transactionService.getTransactions(userId, start, end)).isSameAs(inRange);
        assertThat(transactionService.getTransactions(userId, null, null)).isSameAs(all);
        assertThat(transactionService.getTransactions(userId, start, null)).isSameAs(all);
        assertThat(transactionService.getTransactionsByCategory(userId, "TRAVEL")).isSameAs(inRange);
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

    private void stubPlaidTransactions(com.plaid.client.model.Transaction... transactions) throws Exception {
        stubSyncPages(page(false, "cursor-1", List.of(transactions), List.of(), List.of()));
    }

    private void stubSyncPages(TransactionsSyncResponse... pages) throws Exception {
        @SuppressWarnings("unchecked")
        Response<TransactionsSyncResponse>[] responses = new Response[pages.length];
        for (int i = 0; i < pages.length; i++) {
            responses[i] = Response.success(pages[i]);
        }
        stubSyncResponses(responses);
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private void stubSyncResponses(Response<TransactionsSyncResponse>... responses) throws Exception {
        List<Call<TransactionsSyncResponse>> calls = new java.util.ArrayList<>();
        for (Response<TransactionsSyncResponse> response : responses) {
            Call<TransactionsSyncResponse> call = mock(Call.class);
            when(call.execute()).thenReturn(response);
            calls.add(call);
        }
        org.mockito.stubbing.OngoingStubbing<Call<TransactionsSyncResponse>> stubbing =
                when(plaidClient.transactionsSync(any(TransactionsSyncRequest.class)));
        for (Call<TransactionsSyncResponse> call : calls) {
            stubbing = stubbing.thenReturn(call);
        }
    }

    private Response<TransactionsSyncResponse> mutationError() {
        return Response.error(400, ResponseBody.create(
                "{\"error_code\": \"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION\"}", MediaType.get("application/json")));
    }

    private TransactionsSyncResponse page(boolean hasMore, String nextCursor,
                                          List<com.plaid.client.model.Transaction> added,
                                          List<com.plaid.client.model.Transaction> modified,
                                          List<RemovedTransaction> removed) {
        return new TransactionsSyncResponse()
                .added(added)
                .modified(modified)
                .removed(removed)
                .hasMore(hasMore)
                .nextCursor(nextCursor);
    }

    private Transaction existing(String plaidTransactionId) {
        Transaction transaction = new Transaction();
        transaction.setPlaidTransactionId(plaidTransactionId);
        transaction.setAccountId(checking.getId());
        transaction.setUserId(userId);
        return transaction;
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

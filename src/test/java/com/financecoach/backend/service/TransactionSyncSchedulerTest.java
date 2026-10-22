package com.financecoach.backend.service;

import com.financecoach.backend.model.BankAccount;
import com.financecoach.backend.model.PlaidItem;
import com.financecoach.backend.repository.BankAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionSyncSchedulerTest {

    @Mock private TransactionService transactionService;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private PlaidService plaidService;

    @InjectMocks
    private TransactionSyncScheduler scheduler;

    @Test
    void syncAllAccounts_syncsEachBankLoginOnce() {
        PlaidItem item = item();
        BankAccount checking = account();
        BankAccount savings = account();
        when(bankAccountRepository.findByIsActive(true)).thenReturn(List.of(checking, savings));
        when(plaidService.getItem(checking)).thenReturn(item);
        when(plaidService.getItem(savings)).thenReturn(item);

        scheduler.syncAllAccounts();

        verify(transactionService, times(1)).syncItem(item);
    }

    @Test
    void syncAllAccounts_keepsGoingWhenOneItemFails() {
        PlaidItem broken = item();
        PlaidItem healthy = item();
        BankAccount a = account();
        BankAccount b = account();
        BankAccount unresolvable = account();
        when(bankAccountRepository.findByIsActive(true)).thenReturn(List.of(unresolvable, a, b));
        when(plaidService.getItem(unresolvable)).thenThrow(new RuntimeException("ITEM_LOGIN_REQUIRED"));
        when(plaidService.getItem(a)).thenReturn(broken);
        when(plaidService.getItem(b)).thenReturn(healthy);
        when(transactionService.syncItem(broken)).thenThrow(new RuntimeException("Plaid down"));

        scheduler.syncAllAccounts();

        verify(transactionService).syncItem(broken);
        verify(transactionService).syncItem(healthy);
    }

    @Test
    void syncAllAccounts_skipsABankWaitingForTheUserToSignInAgain() {
        PlaidItem expired = item();
        expired.setNeedsReconnect(true);
        PlaidItem healthy = item();
        BankAccount a = account();
        BankAccount b = account();
        when(bankAccountRepository.findByIsActive(true)).thenReturn(List.of(a, b));
        when(plaidService.getItem(a)).thenReturn(expired);
        when(plaidService.getItem(b)).thenReturn(healthy);

        scheduler.syncAllAccounts();

        verify(transactionService, never()).syncItem(expired);
        verify(transactionService).syncItem(healthy);
    }

    @Test
    void syncAllAccounts_withNoAccounts_doesNothing() {
        when(bankAccountRepository.findByIsActive(true)).thenReturn(List.of());

        scheduler.syncAllAccounts();

        verify(transactionService, never()).syncItem(any());
    }

    private PlaidItem item() {
        PlaidItem item = new PlaidItem();
        item.setId(UUID.randomUUID());
        return item;
    }

    // BankAccount uses Lombok @Data equality, so test accounts need distinct IDs
    private BankAccount account() {
        BankAccount account = new BankAccount();
        account.setId(UUID.randomUUID());
        return account;
    }
}

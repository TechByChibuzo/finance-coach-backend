package com.financecoach.backend.service;

import com.financecoach.backend.exception.PlaidIntegrationException;
import com.financecoach.backend.exception.UserNotFoundException;
import com.financecoach.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private PlaidService plaidService;
    @Mock private TransactionEmbeddingRepository transactionEmbeddingRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private HoldingRepository holdingRepository;
    @Mock private PortfolioSnapshotRepository portfolioSnapshotRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private PlaidItemRepository plaidItemRepository;
    @Mock private NetWorthSnapshotRepository netWorthSnapshotRepository;
    @Mock private ManualAssetRepository manualAssetRepository;
    @Mock private ManualLiabilityRepository manualLiabilityRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private ConversationMessageRepository conversationMessageRepository;
    @Mock private SpendingAnomalyRepository spendingAnomalyRepository;
    @Mock private UsageTrackingRepository usageTrackingRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private UserSubscriptionRepository userSubscriptionRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private com.financecoach.backend.repository.CategoryRuleRepository categoryRuleRepository;
    @Mock private com.financecoach.backend.repository.SavingsGoalRepository savingsGoalRepository;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private AccountDeletionService accountDeletionService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @Test
    void deleteAccount_cancelsBilling_revokesBanks_thenDeletesEverything() {
        when(userRepository.existsById(userId)).thenReturn(true);
        runTransactionCallbacks();

        accountDeletionService.deleteAccount(userId);

        InOrder order = inOrder(subscriptionService, plaidService, bankAccountRepository, userRepository);
        order.verify(subscriptionService).cancelAllForAccountDeletion(userId);
        order.verify(plaidService).removeAllItemsForUser(userId);
        order.verify(bankAccountRepository).deleteAllByUserId(userId);
        order.verify(userRepository).deleteById(userId);

        verify(transactionEmbeddingRepository).deleteAllByUserId(userId);
        verify(transactionRepository).deleteAllByUserId(userId);
        verify(holdingRepository).deleteAllByUserId(userId);
        verify(portfolioSnapshotRepository).deleteAllByUserId(userId);
        verify(plaidItemRepository).deleteAllByUserId(userId);
        verify(netWorthSnapshotRepository).deleteAllByUserId(userId);
        verify(manualAssetRepository).deleteAllByUserId(userId);
        verify(manualLiabilityRepository).deleteAllByUserId(userId);
        verify(budgetRepository).deleteAllByUserId(userId);
        verify(conversationMessageRepository).deleteAllByUserId(userId);
        verify(spendingAnomalyRepository).deleteAllByUserId(userId);
        verify(usageTrackingRepository).deleteAllByUserId(userId);
        verify(passwordResetTokenRepository).deleteByUserId(userId);
        verify(userSubscriptionRepository).deleteAllByUserId(userId);
        verify(paymentRepository).deleteAllByUserId(userId);
        verify(categoryRuleRepository).deleteByUserId(userId);
        verify(savingsGoalRepository).deleteAllByUserId(userId);
    }

    @Test
    void deleteAccount_whenStripeCancellationFails_deletesNothing() {
        when(userRepository.existsById(userId)).thenReturn(true);
        doThrow(new RuntimeException("Could not cancel your subscription"))
                .when(subscriptionService).cancelAllForAccountDeletion(userId);

        assertThatThrownBy(() -> accountDeletionService.deleteAccount(userId))
                .hasMessageContaining("Could not cancel");

        verifyNoInteractions(plaidService, transactionRepository, bankAccountRepository);
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void deleteAccount_whenPlaidRevocationFails_deletesNothingLocally() {
        when(userRepository.existsById(userId)).thenReturn(true);
        doThrow(new PlaidIntegrationException("Plaid down"))
                .when(plaidService).removeAllItemsForUser(userId);

        assertThatThrownBy(() -> accountDeletionService.deleteAccount(userId))
                .isInstanceOf(PlaidIntegrationException.class);

        verifyNoInteractions(transactionTemplate, transactionRepository, bankAccountRepository);
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void deleteAccount_forUnknownUser_throwsNotFound() {
        when(userRepository.existsById(userId)).thenReturn(false);

        assertThatThrownBy(() -> accountDeletionService.deleteAccount(userId))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(subscriptionService, plaidService);
    }

    @SuppressWarnings("unchecked")
    private void runTransactionCallbacks() {
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }
}

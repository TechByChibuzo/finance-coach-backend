package com.financecoach.backend.service;

import com.financecoach.backend.exception.UserNotFoundException;
import com.financecoach.backend.repository.CategoryRuleRepository;
import com.financecoach.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Deletes a user's account and everything tied to it.
 *
 * Tables reference users by a plain user_id column with no foreign keys, so nothing
 * cascades: every table has to be cleared explicitly.
 *
 * External services are handled first, outside the database transaction: Stripe billing is
 * cancelled and the customer deleted, then Plaid items are revoked. If either fails, nothing
 * is deleted locally and the request can simply be retried; every step is idempotent
 * (already-cancelled subscriptions, deleted customers and revoked items are skipped).
 */
@Service
public class AccountDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final PlaidService plaidService;
    private final TransactionEmbeddingRepository transactionEmbeddingRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingRepository holdingRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final BankAccountRepository bankAccountRepository;
    private final PlaidItemRepository plaidItemRepository;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;
    private final ManualAssetRepository manualAssetRepository;
    private final ManualLiabilityRepository manualLiabilityRepository;
    private final BudgetRepository budgetRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final SpendingAnomalyRepository spendingAnomalyRepository;
    private final UsageTrackingRepository usageTrackingRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final TransactionTemplate transactionTemplate;

    public AccountDeletionService(UserRepository userRepository,
                                  SubscriptionService subscriptionService,
                                  PlaidService plaidService,
                                  TransactionEmbeddingRepository transactionEmbeddingRepository,
                                  TransactionRepository transactionRepository,
                                  HoldingRepository holdingRepository,
                                  PortfolioSnapshotRepository portfolioSnapshotRepository,
                                  BankAccountRepository bankAccountRepository,
                                  PlaidItemRepository plaidItemRepository,
                                  NetWorthSnapshotRepository netWorthSnapshotRepository,
                                  ManualAssetRepository manualAssetRepository,
                                  ManualLiabilityRepository manualLiabilityRepository,
                                  BudgetRepository budgetRepository,
                                  ConversationMessageRepository conversationMessageRepository,
                                  SpendingAnomalyRepository spendingAnomalyRepository,
                                  UsageTrackingRepository usageTrackingRepository,
                                  PasswordResetTokenRepository passwordResetTokenRepository,
                                  UserSubscriptionRepository userSubscriptionRepository,
                                  PaymentRepository paymentRepository,
                                  CategoryRuleRepository categoryRuleRepository,
                                  TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
        this.plaidService = plaidService;
        this.transactionEmbeddingRepository = transactionEmbeddingRepository;
        this.transactionRepository = transactionRepository;
        this.holdingRepository = holdingRepository;
        this.portfolioSnapshotRepository = portfolioSnapshotRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.plaidItemRepository = plaidItemRepository;
        this.netWorthSnapshotRepository = netWorthSnapshotRepository;
        this.manualAssetRepository = manualAssetRepository;
        this.manualLiabilityRepository = manualLiabilityRepository;
        this.budgetRepository = budgetRepository;
        this.conversationMessageRepository = conversationMessageRepository;
        this.spendingAnomalyRepository = spendingAnomalyRepository;
        this.usageTrackingRepository = usageTrackingRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.userSubscriptionRepository = userSubscriptionRepository;
        this.paymentRepository = paymentRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public void deleteAccount(UUID userId) {
        logger.info("Deleting account for user: {}", userId);

        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }

        // 1. Stop billing and remove the Stripe customer. Throws (deleting nothing) on failure.
        subscriptionService.cancelAllForAccountDeletion(userId);

        // 2. Revoke bank connections at Plaid. Throws (deleting nothing) on failure.
        plaidService.removeAllItemsForUser(userId);

        // 3. Delete the user's data, then the user, in one short transaction
        transactionTemplate.executeWithoutResult(status -> purgeLocalData(userId));

        logger.info("Account deleted for user: {}", userId);
    }

    private void purgeLocalData(UUID userId) {
        transactionEmbeddingRepository.deleteAllByUserId(userId);
        transactionRepository.deleteAllByUserId(userId);
        holdingRepository.deleteAllByUserId(userId);
        portfolioSnapshotRepository.deleteAllByUserId(userId);
        bankAccountRepository.deleteAllByUserId(userId);
        plaidItemRepository.deleteAllByUserId(userId);
        netWorthSnapshotRepository.deleteAllByUserId(userId);
        manualAssetRepository.deleteAllByUserId(userId);
        manualLiabilityRepository.deleteAllByUserId(userId);
        budgetRepository.deleteAllByUserId(userId);
        conversationMessageRepository.deleteAllByUserId(userId);
        spendingAnomalyRepository.deleteAllByUserId(userId);
        usageTrackingRepository.deleteAllByUserId(userId);
        passwordResetTokenRepository.deleteByUserId(userId);
        userSubscriptionRepository.deleteAllByUserId(userId);
        paymentRepository.deleteAllByUserId(userId);
        categoryRuleRepository.deleteByUserId(userId);
        userRepository.deleteById(userId);
    }
}

package com.financecoach.backend.service;

import com.financecoach.backend.model.Transaction;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Shared rules for which transactions count as spending or income.
 *
 * Money moving between the user's own accounts is not spending or income: a transfer
 * from checking to savings, or paying off a credit card whose purchases are already
 * counted. Counting both sides double-counts the same money.
 *
 * Plaid sign convention: positive amount = money out, negative = money in.
 */
public final class TransactionFilters {

    // Plaid Personal Finance Category primaries that move money between own accounts
    private static final Set<String> TRANSFER_CATEGORIES = Set.of("TRANSFER_IN", "TRANSFER_OUT");

    // Plaid PFC detailed category for paying a credit card bill
    private static final String CREDIT_CARD_PAYMENT = "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT";

    private TransactionFilters() {
    }

    public static boolean isTransfer(Transaction t) {
        String category = t.getCategory();
        String subcategory = t.getSubcategory();

        if (category != null && TRANSFER_CATEGORIES.contains(category.toUpperCase())) {
            return true;
        }
        if (CREDIT_CARD_PAYMENT.equalsIgnoreCase(subcategory)) {
            return true;
        }

        // Legacy Plaid categories (["Transfer", ...], ["Payment", "Credit Card"])
        return "Transfer".equalsIgnoreCase(category)
                || ("Payment".equalsIgnoreCase(category) && "Credit Card".equalsIgnoreCase(subcategory));
    }

    /** Money out that isn't a transfer between the user's own accounts */
    public static boolean isSpending(Transaction t) {
        return t.getAmount().compareTo(BigDecimal.ZERO) > 0 && !isTransfer(t);
    }

    /** Money in that isn't a transfer between the user's own accounts */
    public static boolean isIncome(Transaction t) {
        return t.getAmount().compareTo(BigDecimal.ZERO) < 0 && !isTransfer(t);
    }
}

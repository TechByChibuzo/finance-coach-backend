package com.financecoach.backend.service;

import com.financecoach.backend.dto.TransactionSplitRequest;
import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Notes, hiding a transaction from totals, and splitting one purchase across categories.
 *
 * A split keeps the original transaction (so a bank update can still find it) but leaves it out
 * of every total; each part is a transaction of its own with its own category, so everything
 * that adds up spending by category counts the parts without knowing about splits.
 */
@Service
public class TransactionEditService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionEditService.class);

    private final TransactionRepository transactionRepository;
    private final VectorStoreService vectorStoreService;

    public TransactionEditService(TransactionRepository transactionRepository, VectorStoreService vectorStoreService) {
        this.transactionRepository = transactionRepository;
        this.vectorStoreService = vectorStoreService;
    }

    public Transaction updateDetails(UUID userId, UUID transactionId, String notes, Boolean hidden) {
        Transaction transaction = owned(userId, transactionId);
        if (notes != null) {
            transaction.setNotes(notes.isBlank() ? null : notes.trim());
        }
        boolean wasExcluded = TransactionFilters.isExcluded(transaction);
        if (hidden != null) {
            transaction.setHidden(hidden);
        }
        Transaction saved = transactionRepository.save(transaction);

        // The coach shouldn't count what the user hid either
        if (!wasExcluded && TransactionFilters.isExcluded(saved)) {
            vectorStoreService.removeTransaction(saved.getPlaidTransactionId());
        } else if (wasExcluded && !TransactionFilters.isExcluded(saved)) {
            vectorStoreService.indexTransactionsAsync(List.of(saved));
        }
        return saved;
    }

    /**
     * Replaces any earlier split of the transaction with these parts
     */
    public List<Transaction> split(UUID userId, UUID transactionId, List<TransactionSplitRequest.Part> parts) {
        Transaction original = owned(userId, transactionId);
        if (original.getSplitFromId() != null) {
            throw new ValidationException("This is already part of a split. Edit the split instead.");
        }

        int sign = original.getAmount().signum();
        BigDecimal total = BigDecimal.ZERO;
        List<TransactionCategorizer.Category> categories = new ArrayList<>();
        for (TransactionSplitRequest.Part part : parts) {
            if (part.amount().signum() == 0 || part.amount().signum() != sign || part.amount().scale() > 2) {
                throw new ValidationException("Each part needs an amount in the same direction as the transaction.");
            }
            categories.add(CategoryRuleService.parse(part.category()));
            total = total.add(part.amount());
        }
        if (total.compareTo(original.getAmount()) != 0) {
            throw new ValidationException("The parts add up to " + total.abs().toPlainString()
                    + " but the transaction is " + original.getAmount().abs().toPlainString() + ".");
        }

        transactionRepository.deleteBySplitFromId(original.getId());
        List<Transaction> children = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            children.add(part(original, i + 1, parts.get(i).amount(), categories.get(i)));
        }
        List<Transaction> saved = transactionRepository.saveAll(children);

        original.setSplit(true);
        transactionRepository.save(original);
        vectorStoreService.removeTransaction(original.getPlaidTransactionId());
        vectorStoreService.indexTransactionsAsync(saved);
        logger.info("User {} split transaction {} into {} parts", userId, transactionId, saved.size());
        return saved;
    }

    /**
     * Puts a split transaction back together; given one of its parts, undoes that part's split
     */
    public Transaction unsplit(UUID userId, UUID transactionId) {
        Transaction transaction = owned(userId, transactionId);
        Transaction original = transaction.getSplitFromId() == null ? transaction : owned(userId, transaction.getSplitFromId());

        transactionRepository.deleteBySplitFromId(original.getId());
        original.setSplit(false);
        Transaction saved = transactionRepository.save(original);
        if (!TransactionFilters.isExcluded(saved)) {
            vectorStoreService.indexTransactionsAsync(List.of(saved));
        }
        return saved;
    }

    public List<Transaction> getParts(UUID userId, UUID transactionId) {
        return transactionRepository.findBySplitFromIdOrderByAmountDesc(owned(userId, transactionId).getId());
    }

    private Transaction owned(UUID userId, UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new UnauthorizedAccessException("transaction"));
    }

    private static Transaction part(Transaction original, int number, BigDecimal amount,
                                    TransactionCategorizer.Category category) {
        Transaction part = new Transaction();
        part.setUserId(original.getUserId());
        part.setAccountId(original.getAccountId());
        // Unique, and never an ID Plaid would send
        part.setPlaidTransactionId(original.getPlaidTransactionId() + "#split-" + number);
        part.setSplitFromId(original.getId());
        part.setDate(original.getDate());
        part.setAmount(amount);
        part.setMerchantName(original.getMerchantName());
        part.setName(original.getName());
        part.setCategory(category.category());
        part.setSubcategory(category.subcategory());
        part.setCategoryOverridden(true);
        part.setPaymentChannel(original.getPaymentChannel());
        part.setPending(original.getPending());
        part.setCurrencyCode(original.getCurrencyCode());
        part.setLogoUrl(original.getLogoUrl());
        part.setMerchantWebsite(original.getMerchantWebsite());
        // Undoing the import removes the parts too
        part.setImportBatchId(original.getImportBatchId());
        part.setImportAccountName(original.getImportAccountName());
        return part;
    }
}

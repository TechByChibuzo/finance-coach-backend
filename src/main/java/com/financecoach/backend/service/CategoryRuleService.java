package com.financecoach.backend.service;

import com.financecoach.backend.exception.UnauthorizedAccessException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.CategoryRule;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.CategoryRuleRepository;
import com.financecoach.backend.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lets users correct a transaction's category, and remember the correction for a merchant.
 * Corrected transactions are marked so a later Plaid update doesn't put the old category back.
 */
@Service
public class CategoryRuleService {

    private static final Logger logger = LoggerFactory.getLogger(CategoryRuleService.class);

    private final CategoryRuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final VectorStoreService vectorStoreService;

    public CategoryRuleService(CategoryRuleRepository ruleRepository,
                               TransactionRepository transactionRepository,
                               VectorStoreService vectorStoreService) {
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.vectorStoreService = vectorStoreService;
    }

    public Transaction updateCategory(UUID userId, UUID transactionId, String code, boolean remember) {
        TransactionCategorizer.Category category = parse(code);
        Transaction transaction = transactionRepository.findById(transactionId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new UnauthorizedAccessException("transaction"));

        List<Transaction> changed = new ArrayList<>();
        if (remember) {
            String key = matchKey(transaction);
            CategoryRule rule = ruleRepository.findByUserIdAndMatchKey(userId, key).orElseGet(CategoryRule::new);
            rule.setUserId(userId);
            rule.setMatchKey(key);
            rule.setDisplayName(displayName(transaction));
            rule.setCategory(category.category());
            rule.setSubcategory(category.subcategory());
            ruleRepository.save(rule);
            for (Transaction t : transactionRepository.findByUserId(userId)) {
                // Parts of a split keep the categories the user gave them
                if (t.getSplitFromId() == null && key.equals(matchKey(t))) {
                    apply(t, category);
                    changed.add(t);
                }
            }
            logger.info("Category rule for user {}: {} transactions now {}", userId, changed.size(), category.category());
        } else {
            apply(transaction, category);
            changed.add(transaction);
        }

        List<Transaction> saved = transactionRepository.saveAll(changed);
        // The coach's embeddings include the category, so rebuild them for what changed
        saved.forEach(t -> vectorStoreService.removeTransaction(t.getPlaidTransactionId()));
        vectorStoreService.indexTransactionsAsync(saved);
        return saved.stream().filter(t -> t.getId().equals(transactionId)).findFirst().orElse(transaction);
    }

    /**
     * Applies the user's rules to transactions about to be saved (new syncs and imports)
     */
    public void applyRules(UUID userId, List<Transaction> transactions) {
        if (transactions.isEmpty()) {
            return;
        }
        Map<String, CategoryRule> rules = ruleRepository.findByUserIdOrderByDisplayNameAsc(userId).stream()
                .collect(Collectors.toMap(CategoryRule::getMatchKey, Function.identity(), (a, b) -> a));
        if (rules.isEmpty()) {
            return;
        }
        for (Transaction t : transactions) {
            CategoryRule rule = rules.get(matchKey(t));
            if (rule != null) {
                apply(t, new TransactionCategorizer.Category(rule.getCategory(), rule.getSubcategory()));
            }
        }
    }

    public List<CategoryRule> getRules(UUID userId) {
        return ruleRepository.findByUserIdOrderByDisplayNameAsc(userId);
    }

    /**
     * Stops applying the rule to new transactions; transactions it already changed keep their category
     */
    public void deleteRule(UUID userId, UUID ruleId) {
        CategoryRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new UnauthorizedAccessException("category rule"));
        ruleRepository.delete(rule);
    }

    static String matchKey(Transaction t) {
        return displayName(t).trim().toLowerCase(Locale.ROOT);
    }

    private static String displayName(Transaction t) {
        return t.getMerchantName() != null && !t.getMerchantName().isBlank() ? t.getMerchantName() : t.getName();
    }

    private static void apply(Transaction t, TransactionCategorizer.Category category) {
        t.setCategory(category.category());
        t.setSubcategory(category.subcategory());
        t.setCategoryOverridden(true);
    }

    static TransactionCategorizer.Category parse(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!TransactionCategorizer.CATEGORIES.contains(normalized)) {
            throw new ValidationException("Unknown category: " + code, Map.of("category", "Not a known category"));
        }
        return TransactionCategorizer.toCategory(normalized);
    }
}

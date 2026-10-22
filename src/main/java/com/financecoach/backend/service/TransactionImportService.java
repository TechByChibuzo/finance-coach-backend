package com.financecoach.backend.service;

import com.financecoach.backend.dto.imports.TransactionImportRequest;
import com.financecoach.backend.dto.imports.TransactionImportResponse;
import com.financecoach.backend.model.Transaction;
import com.financecoach.backend.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Imports transactions from a bank's CSV export, for banks that aren't linked through Plaid.
 *
 * Each row gets a deterministic ID from its content, so importing the same file again skips
 * what's already there. Imported transactions hang off a stable account ID derived from the
 * account name the user gives, without creating a Plaid bank account.
 */
@Service
public class TransactionImportService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionImportService.class);
    private static final int LOOKUP_CHUNK = 1000;

    private final TransactionRepository transactionRepository;
    private final TransactionCategorizer categorizer;
    private final VectorStoreService vectorStoreService;

    public TransactionImportService(TransactionRepository transactionRepository,
                                    TransactionCategorizer categorizer,
                                    VectorStoreService vectorStoreService) {
        this.transactionRepository = transactionRepository;
        this.categorizer = categorizer;
        this.vectorStoreService = vectorStoreService;
    }

    public TransactionImportResponse importTransactions(UUID userId, TransactionImportRequest request) {
        String accountName = request.accountName().trim();
        UUID accountId = importedAccountId(userId, accountName);

        // Identical rows in one file (two $4.50 coffees on the same day) are numbered so both import
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> ids = new ArrayList<>(request.rows().size());
        for (TransactionImportRequest.Row row : request.rows()) {
            String content = row.date() + "|" + row.amount().stripTrailingZeros().toPlainString() + "|"
                    + row.description().trim().toLowerCase(Locale.ROOT);
            int occurrence = occurrences.merge(content, 1, Integer::sum);
            ids.add(rowId(userId, accountName, content, occurrence));
        }

        Set<String> existing = existingIds(ids);
        List<TransactionImportRequest.Row> newRows = new ArrayList<>();
        List<String> newIds = new ArrayList<>();
        Set<String> seen = new HashSet<>(existing);
        for (int i = 0; i < ids.size(); i++) {
            if (seen.add(ids.get(i))) {
                newRows.add(request.rows().get(i));
                newIds.add(ids.get(i));
            }
        }

        UUID batchId = UUID.randomUUID();
        if (newRows.isEmpty()) {
            return new TransactionImportResponse(batchId, 0, request.rows().size());
        }

        List<TransactionCategorizer.Category> categories = categorizer.categorize(newRows.stream()
                .map(r -> new TransactionCategorizer.Input(r.description().trim(), r.amount()))
                .toList());

        List<Transaction> toSave = new ArrayList<>(newRows.size());
        for (int i = 0; i < newRows.size(); i++) {
            TransactionImportRequest.Row row = newRows.get(i);
            Transaction t = new Transaction();
            t.setUserId(userId);
            t.setAccountId(accountId);
            t.setPlaidTransactionId(newIds.get(i));
            t.setDate(row.date());
            t.setAmount(row.amount());
            t.setName(row.description().trim());
            t.setCategory(categories.get(i).category());
            t.setSubcategory(categories.get(i).subcategory());
            t.setPending(false);
            t.setImportBatchId(batchId);
            t.setImportAccountName(accountName);
            t.setCreatedAt(LocalDateTime.now());
            toSave.add(t);
        }

        // Saved (and committed) before indexing: the background embedding only indexes rows that exist
        List<Transaction> saved = transactionRepository.saveAll(toSave);
        vectorStoreService.indexTransactionsAsync(saved);

        logger.info("Imported {} transactions for user {} into '{}' ({} duplicates skipped)",
                saved.size(), userId, accountName, request.rows().size() - saved.size());
        return new TransactionImportResponse(batchId, saved.size(), request.rows().size() - saved.size());
    }

    /**
     * Removes everything one import added (their embeddings go with them via the foreign key)
     */
    public int undoImport(UUID userId, UUID batchId) {
        int deleted = transactionRepository.deleteImportBatch(userId, batchId);
        logger.info("Undid import {} for user {}: {} transactions removed", batchId, userId, deleted);
        return deleted;
    }

    static UUID importedAccountId(UUID userId, String accountName) {
        return UUID.nameUUIDFromBytes(("csv-account:" + userId + ":" + accountName.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String rowId(UUID userId, String accountName, String content, int occurrence) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (userId + "|" + accountName.toLowerCase(Locale.ROOT) + "|" + content + "|" + occurrence)
                            .getBytes(StandardCharsets.UTF_8));
            return "csv_" + HexFormat.of().formatHex(hash, 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Set<String> existingIds(List<String> ids) {
        Set<String> existing = new HashSet<>();
        for (int start = 0; start < ids.size(); start += LOOKUP_CHUNK) {
            existing.addAll(transactionRepository.findExistingPlaidTransactionIds(
                    ids.subList(start, Math.min(start + LOOKUP_CHUNK, ids.size()))));
        }
        return existing;
    }
}

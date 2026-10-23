package com.financecoach.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Constraints Hibernate's ddl-auto can't express. Runs at startup, after the tables exist,
 * and is safe to run every time.
 *
 * transaction_embeddings.transaction_id references transactions.plaid_transaction_id with
 * ON DELETE CASCADE: deleting a transaction (or a whole account) removes its embedding, and an
 * embedding written in the background for a transaction that's being deleted fails instead of
 * outliving it.
 *
 * spending_anomalies, transactions, bank_accounts and plaid_items reference users.id with
 * ON DELETE CASCADE: anomaly detection and bank syncs run in the background, and a row they save
 * while that user's account is being deleted fails instead of outliving it (for plaid_items, with
 * the bank access token). transactions don't reference bank_accounts: CSV imports use a derived
 * account ID that has no bank_accounts row.
 *
 * usage_tracking has a unique index on (user_id, feature_name, period_start), which the atomic
 * usage reservation needs.
 *
 * Each step runs on its own (not in one transaction), so one failing doesn't abort the others.
 */
@Component
@Order(0)
public class DatabaseConstraints implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConstraints.class);

    static final String EMBEDDING_FK = "fk_transaction_embeddings_transaction";
    static final String USAGE_UNIQUE_INDEX = "uk_usage_tracking_user_feature_period";
    static final String[] USER_OWNED_TABLES = {"spending_anomalies", "transactions", "bank_accounts", "plaid_items"};

    private final JdbcTemplate jdbcTemplate;

    public DatabaseConstraints(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            addEmbeddingForeignKey();
        } catch (Exception e) {
            // Never block startup over this; the guarded insert in VectorStoreService still applies
            logger.error("Could not add {}: {}", EMBEDDING_FK, e.getMessage());
        }
        try {
            addUsageUniqueIndex();
        } catch (Exception e) {
            logger.error("Could not add {}: {}", USAGE_UNIQUE_INDEX, e.getMessage());
        }
        for (String table : USER_OWNED_TABLES) {
            String constraint = "fk_" + table + "_user";
            try {
                addUserForeignKey(table, constraint);
            } catch (Exception e) {
                logger.error("Could not add {}: {}", constraint, e.getMessage());
            }
        }
    }

    private void addUserForeignKey(String table, String constraint) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?", Integer.class, constraint);
        if (existing != null && existing > 0) {
            return;
        }

        // Rows of users that no longer exist would block the constraint (and shouldn't be kept)
        int orphans = jdbcTemplate.update(
                "DELETE FROM " + table + " r WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = r.user_id)");

        jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
                + " FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE");

        logger.info("Added {} (removed {} rows of deleted users)", constraint, orphans);
    }

    /**
     * One usage counter per user, feature and month. reserveUsage relies on it (ON CONFLICT).
     * Counters duplicated by the old read-then-write race are merged first, keeping the highest count.
     */
    private void addUsageUniqueIndex() {
        int merged = jdbcTemplate.update("""
                DELETE FROM usage_tracking a USING usage_tracking b
                WHERE a.user_id = b.user_id AND a.feature_name = b.feature_name
                  AND a.period_start = b.period_start
                  AND (a.usage_count < b.usage_count OR (a.usage_count = b.usage_count AND a.id < b.id))
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + USAGE_UNIQUE_INDEX
                + " ON usage_tracking (user_id, feature_name, period_start)");
        if (merged > 0) {
            logger.info("Merged {} duplicate usage counters", merged);
        }
    }

    private void addEmbeddingForeignKey() {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?", Integer.class, EMBEDDING_FK);
        if (existing != null && existing > 0) {
            return;
        }

        // Embeddings whose transaction no longer exists would block the constraint
        int orphans = jdbcTemplate.update(
                "DELETE FROM transaction_embeddings e WHERE NOT EXISTS "
                        + "(SELECT 1 FROM transactions t WHERE t.plaid_transaction_id = e.transaction_id)");

        jdbcTemplate.execute("ALTER TABLE transaction_embeddings ADD CONSTRAINT " + EMBEDDING_FK
                + " FOREIGN KEY (transaction_id) REFERENCES transactions (plaid_transaction_id) ON DELETE CASCADE");

        logger.info("Added {} (removed {} orphaned embeddings)", EMBEDDING_FK, orphans);
    }
}

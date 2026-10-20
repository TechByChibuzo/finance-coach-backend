package com.financecoach.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Constraints Hibernate's ddl-auto can't express. Runs at startup, after the tables exist,
 * and is safe to run every time.
 *
 * transaction_embeddings.transaction_id references transactions.plaid_transaction_id with
 * ON DELETE CASCADE: deleting a transaction (or a whole account) removes its embedding, and an
 * embedding written in the background for a transaction that's being deleted fails instead of
 * outliving it.
 */
@Component
@Order(0)
public class DatabaseConstraints implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConstraints.class);

    static final String EMBEDDING_FK = "fk_transaction_embeddings_transaction";

    private final JdbcTemplate jdbcTemplate;

    public DatabaseConstraints(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            addEmbeddingForeignKey();
        } catch (Exception e) {
            // Never block startup over this; the guarded insert in VectorStoreService still applies
            logger.error("Could not add {}: {}", EMBEDDING_FK, e.getMessage());
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

package com.financecoach.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseConstraintsTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DatabaseConstraints databaseConstraints;

    @Test
    void addsTheCascadingForeignKey_afterRemovingOrphanedEmbeddings() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);

        databaseConstraints.run(null);

        verify(jdbcTemplate).update(contains("DELETE FROM transaction_embeddings"));
        verify(jdbcTemplate).execute(contains("REFERENCES transactions (plaid_transaction_id) ON DELETE CASCADE"));
        verify(jdbcTemplate).update(contains("DELETE FROM usage_tracking"));
        verify(jdbcTemplate).execute(contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_usage_tracking_user_feature_period"));
        verify(jdbcTemplate).update(contains("DELETE FROM spending_anomalies"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE spending_anomalies ADD CONSTRAINT fk_spending_anomalies_user"));
    }

    @Test
    void tiesBankDataToTheUser_soASyncFinishingAfterAccountDeletionCantWriteItBack() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0);

        databaseConstraints.run(null);

        for (String table : new String[] {"transactions", "bank_accounts", "plaid_items"}) {
            verify(jdbcTemplate).update(contains("DELETE FROM " + table));
            verify(jdbcTemplate).execute(contains("ALTER TABLE " + table + " ADD CONSTRAINT fk_" + table
                    + "_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"));
        }
    }

    @Test
    void doesNothingWhenTheConstraintAlreadyExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(1);

        databaseConstraints.run(null);

        verify(jdbcTemplate, never()).execute(contains("ADD CONSTRAINT"));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM transaction_embeddings"));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM spending_anomalies"));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM transactions"));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM plaid_items"));
        // The usage index is created with IF NOT EXISTS, so it's always (cheaply) ensured
        verify(jdbcTemplate).execute(contains("CREATE UNIQUE INDEX IF NOT EXISTS"));
    }

    @Test
    void aFailureIsLogged_notThrown_soStartupContinues() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenThrow(new RuntimeException("relation does not exist"));

        databaseConstraints.run(null);

        verify(jdbcTemplate, never()).execute(contains("ADD CONSTRAINT"));
        // The other steps still run
        verify(jdbcTemplate).execute(contains("CREATE UNIQUE INDEX IF NOT EXISTS"));
    }
}

package com.financecoach.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

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
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(DatabaseConstraints.EMBEDDING_FK)))
                .thenReturn(0);

        databaseConstraints.run(null);

        verify(jdbcTemplate).update(contains("DELETE FROM transaction_embeddings"));
        verify(jdbcTemplate).execute(contains("REFERENCES transactions (plaid_transaction_id) ON DELETE CASCADE"));
        verify(jdbcTemplate).update(contains("DELETE FROM usage_tracking"));
        verify(jdbcTemplate).execute(contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_usage_tracking_user_feature_period"));
    }

    @Test
    void doesNothingWhenTheConstraintAlreadyExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(DatabaseConstraints.EMBEDDING_FK)))
                .thenReturn(1);

        databaseConstraints.run(null);

        verify(jdbcTemplate, never()).execute(contains("ADD CONSTRAINT"));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM transaction_embeddings"));
        // The usage index is created with IF NOT EXISTS, so it's always (cheaply) ensured
        verify(jdbcTemplate).execute(contains("CREATE UNIQUE INDEX IF NOT EXISTS"));
    }

    @Test
    void aFailureIsLogged_notThrown_soStartupContinues() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(DatabaseConstraints.EMBEDDING_FK)))
                .thenThrow(new RuntimeException("relation does not exist"));

        databaseConstraints.run(null);

        verify(jdbcTemplate, never()).execute(contains("ADD CONSTRAINT"));
    }
}

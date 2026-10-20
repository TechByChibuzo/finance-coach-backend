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
    }

    @Test
    void doesNothingWhenTheConstraintAlreadyExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(DatabaseConstraints.EMBEDDING_FK)))
                .thenReturn(1);

        databaseConstraints.run(null);

        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    void aFailureIsLogged_notThrown_soStartupContinues() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(DatabaseConstraints.EMBEDDING_FK)))
                .thenThrow(new RuntimeException("relation does not exist"));

        databaseConstraints.run(null);

        verify(jdbcTemplate, never()).execute(anyString());
    }
}

package com.financecoach.backend.repository;

import com.financecoach.backend.model.PasswordResetToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The password reset rate limit, the "only the newest link works" rule, and cleanup.
 */
class PasswordResetTokenQueriesTest extends PostgresRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 12, 0);

    @Autowired private PasswordResetTokenRepository repository;
    @Autowired private TestEntityManager entityManager;

    private final UUID jane = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void countRecentTokens_countsOnlyThisUsersTokensSinceTheGivenTime() {
        save(jane, "a", NOW.plusHours(1), NOW.minusMinutes(5));
        save(jane, "b", NOW.plusHours(1), NOW.minusMinutes(10));
        save(jane, "old", NOW.plusHours(1), NOW.minusHours(2));
        save(other, "c", NOW.plusHours(1), NOW.minusMinutes(1));

        assertThat(repository.countRecentTokensByUserId(jane, NOW.minusMinutes(15))).isEqualTo(2);
    }

    @Test
    void markActiveTokensAsUsed_retiresOnlyThisUsersUnusedUnexpiredLinks() {
        PasswordResetToken active = save(jane, "active", NOW.plusMinutes(30), NOW);
        PasswordResetToken expired = save(jane, "expired", NOW.minusMinutes(1), NOW.minusHours(2));
        PasswordResetToken othersActive = save(other, "others", NOW.plusMinutes(30), NOW);

        repository.markActiveTokensAsUsed(jane, NOW);
        entityManager.clear();

        assertThat(used(active)).isTrue();
        assertThat(used(expired)).isFalse();
        assertThat(used(othersActive)).isFalse();
    }

    @Test
    void deleteExpiredTokens_andDeleteByUser_removeOnlyWhatTheySay() {
        save(jane, "expired", NOW.minusMinutes(1), NOW.minusHours(2));
        save(jane, "valid", NOW.plusMinutes(30), NOW);
        save(other, "others", NOW.plusMinutes(30), NOW);

        repository.deleteExpiredTokens(NOW);
        assertThat(tokens()).containsExactlyInAnyOrder(hash("valid"), hash("others"));

        repository.deleteByUserId(jane);
        assertThat(tokens()).containsExactly(hash("others"));
    }

    private PasswordResetToken save(UUID userId, String token, LocalDateTime expiry, LocalDateTime createdAt) {
        PasswordResetToken t = new PasswordResetToken(hash(token), userId, expiry);
        t.setCreatedAt(createdAt);
        return repository.saveAndFlush(t);
    }

    private boolean used(PasswordResetToken token) {
        return repository.findById(token.getId()).orElseThrow().isUsed();
    }

    private List<String> tokens() {
        return jdbcTemplate.queryForList("SELECT token FROM password_reset_tokens", String.class);
    }

    // Tokens are stored hashed; any distinct string stands in for one here
    private static String hash(String token) {
        return "hash-" + token;
    }
}

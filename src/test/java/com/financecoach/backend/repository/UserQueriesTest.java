package com.financecoach.backend.repository;

import com.financecoach.backend.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserRepository's targeted updates and row lock, against real Postgres.
 */
class UserQueriesTest extends PostgresRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void updateLastLogin_touchesOnlyLastLogin_soItCantUndoAPasswordChangeMadeMeanwhile() {
        UUID id = userRepository.saveAndFlush(new User("jane@example.com", "old-hash", "Jane")).getId();
        // A password reset commits while the login is in progress
        jdbcTemplate.update("UPDATE users SET password_hash = 'new-hash' WHERE id = ?", id);

        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 9, 30);
        assertThat(userRepository.updateLastLogin(id, now)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id))
                .isEqualTo("new-hash");
        assertThat(jdbcTemplate.queryForObject("SELECT last_login FROM users WHERE id = ?", LocalDateTime.class, id))
                .isEqualTo(now);
    }

    @Test
    void claimWeeklySummary_letsExactlyOneCallerSendEachWeek() {
        UUID id = userRepository.saveAndFlush(new User("jane@example.com", "hash", "Jane")).getId();
        LocalDate week = LocalDate.of(2026, 10, 19);

        assertThat(userRepository.claimWeeklySummary(id, week)).isEqualTo(1);
        assertThat(userRepository.claimWeeklySummary(id, week)).isZero();          // second instance, same week
        assertThat(userRepository.claimWeeklySummary(id, week.minusWeeks(1))).isZero(); // an older week
        assertThat(userRepository.claimWeeklySummary(id, week.plusWeeks(1))).isEqualTo(1);
    }

    @Test
    void theWeeklySummaryPreference_andTokenVersion_areReadAndWrittenByColumn() {
        User user = new User("jane@example.com", "hash", "Jane");
        user.setTokenVersion(3);
        UUID id = userRepository.saveAndFlush(user).getId();

        assertThat(userRepository.updateWeeklySummaryEmails(id, false)).isEqualTo(1);
        entityManager.clear();

        assertThat(userRepository.findById(id)).hasValueSatisfying(u ->
                assertThat(u.getWeeklySummaryEmails()).isFalse());
        assertThat(userRepository.findTokenVersionById(id)).contains(3);
        assertThat(userRepository.findTokenVersionById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // two real, concurrent transactions
    void lockById_makesASecondTransactionWait_untilTheFirstCommits() throws Exception {
        UUID id = new TransactionTemplate(transactionManager).execute(status ->
                userRepository.save(new User("lock@example.com", "hash", "Lock")).getId());
        try {
            CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            CompletableFuture<Long> first = CompletableFuture.supplyAsync(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        userRepository.lockById(id);
                        firstHoldsTheLock.countDown();
                        await(releaseFirst);
                        return System.nanoTime(); // committing right after this
                    }));

            await(firstHoldsTheLock);
            CompletableFuture<Long> second = CompletableFuture.supplyAsync(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        userRepository.lockById(id);
                        return System.nanoTime();
                    }));

            // While the first holds the lock, the second can't get it
            Thread.sleep(500);
            assertThat(second).isNotDone();

            releaseFirst.countDown();
            long firstReleased = first.get(10, TimeUnit.SECONDS);
            long secondAcquired = second.get(10, TimeUnit.SECONDS);
            assertThat(secondAcquired).isGreaterThan(firstReleased);
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

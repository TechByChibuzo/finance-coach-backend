package com.financecoach.backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The atomic reservation that enforces monthly limits (e.g. 5 AI coach messages on the free plan).
 * It relies on the unique index DatabaseConstraints adds.
 */
class UsageReservationTest extends PostgresRepositoryTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCTOBER_END = LocalDate.of(2026, 10, 31);

    @Autowired private UsageTrackingRepository usageRepository;

    @Test
    void countsUsesUpToTheLimit_thenRefuses_withoutCountingTheRefusedOne() {
        UUID userId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertThat(reserve(userId, 5)).as("use %d", i + 1).isEqualTo(1);
        }
        assertThat(reserve(userId, 5)).isZero();

        assertThat(count(userId, OCTOBER)).isEqualTo(5);
    }

    @Test
    void eachMonthHasItsOwnCounter() {
        UUID userId = UUID.randomUUID();
        reserve(userId, 1);

        int november = usageRepository.reserveUsage(userId, "ai_coach",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 1);

        assertThat(november).isEqualTo(1);
        assertThat(count(userId, OCTOBER)).isEqualTo(1);
    }

    @Test
    void releasingGivesAUseBack_butNeverGoesBelowZero() {
        UUID userId = UUID.randomUUID();
        reserve(userId, 5);

        usageRepository.releaseUsage(userId, "ai_coach", OCTOBER);
        usageRepository.releaseUsage(userId, "ai_coach", OCTOBER);

        assertThat(count(userId, OCTOBER)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // each request commits on its own, as in production
    void concurrentRequestsForTheLastUse_onlyOneGetsIt() throws Exception {
        UUID userId = UUID.randomUUID();
        try {
            for (int i = 0; i < 4; i++) {
                reserve(userId, 5);
            }

            int requests = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(requests);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                Callable<Integer> request = () -> {
                    start.await();
                    return reserve(userId, 5);
                };
                results.add(pool.submit(request));
            }
            start.countDown();

            int granted = 0;
            for (Future<Integer> result : results) {
                granted += result.get();
            }
            pool.shutdown();

            assertThat(granted).isEqualTo(1);
            assertThat(count(userId, OCTOBER)).isEqualTo(5);
        } finally {
            jdbcTemplate.update("DELETE FROM usage_tracking WHERE user_id = ?", userId);
        }
    }

    private int reserve(UUID userId, int limit) {
        return usageRepository.reserveUsage(userId, "ai_coach", OCTOBER, OCTOBER_END, limit);
    }

    private int count(UUID userId, LocalDate periodStart) {
        return jdbcTemplate.queryForObject(
                "SELECT usage_count FROM usage_tracking WHERE user_id = ? AND feature_name = 'ai_coach' AND period_start = ?",
                Integer.class, userId, periodStart);
    }
}

package com.financecoach.backend.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final RateLimiter rateLimiter = new RateLimiter(now::get);

    @Test
    void allowsUpToTheLimit_thenRejectsWithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isZero();
        }

        now.addAndGet(20_000);
        assertThat(rateLimiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isEqualTo(40);
    }

    @Test
    void resetsAfterTheWindow() {
        for (int i = 0; i < 4; i++) {
            rateLimiter.tryAcquire("k", 3, Duration.ofMinutes(1));
        }

        now.addAndGet(60_000);
        assertThat(rateLimiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isZero();
    }

    @Test
    void countsKeysSeparately() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire("a", 3, Duration.ofMinutes(1));
        }

        assertThat(rateLimiter.tryAcquire("a", 3, Duration.ofMinutes(1))).isPositive();
        assertThat(rateLimiter.tryAcquire("b", 3, Duration.ofMinutes(1))).isZero();
    }
}

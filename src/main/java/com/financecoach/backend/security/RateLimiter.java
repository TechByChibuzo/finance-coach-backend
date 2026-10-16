package com.financecoach.backend.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fixed-window, in-memory rate limiter.
 *
 * Counts are per application instance; with several instances behind the load balancer
 * the effective limit is multiplied by the instance count. Move to Redis (or WAF rate
 * rules) if that matters.
 */
public class RateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final LongSupplier clockMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    RateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /**
     * Record an attempt for the key.
     *
     * @return 0 if allowed, otherwise the number of seconds until the window resets
     */
    public long tryAcquire(String key, int limit, Duration window) {
        long now = clockMillis.getAsLong();
        long windowMillis = window.toMillis();

        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> now - w.start >= windowMillis);
        }

        Window current = windows.compute(key, (k, w) ->
                w == null || now - w.start >= windowMillis ? new Window(now) : w.increment());

        if (current.count <= limit) {
            return 0;
        }
        return Math.max(1, (current.start + windowMillis - now + 999) / 1000);
    }

    private static final class Window {
        private final long start;
        private final int count;

        private Window(long start) {
            this(start, 1);
        }

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }

        private Window increment() {
            return new Window(start, count + 1);
        }
    }
}

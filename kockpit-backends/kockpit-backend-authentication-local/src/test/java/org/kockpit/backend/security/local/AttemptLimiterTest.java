package org.kockpit.backend.security.local;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-06T10:00:00Z"));

    private final AttemptLimiter limiter = new AttemptLimiter(new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    });

    @Test
    void allows_max_attempts_per_window_then_again_after_it() {
        Duration hour = Duration.ofHours(1);
        assertThat(limiter.tryAcquire("ip", 2, hour)).isTrue();
        assertThat(limiter.tryAcquire("ip", 2, hour)).isTrue();
        assertThat(limiter.tryAcquire("ip", 2, hour)).isFalse();
        assertThat(limiter.isBlocked("ip", 2)).isTrue();
        assertThat(limiter.tryAcquire("other-ip", 2, hour)).isTrue();

        now.set(now.get().plus(hour));
        assertThat(limiter.isBlocked("ip", 2)).isFalse();
        assertThat(limiter.tryAcquire("ip", 2, hour)).isTrue();
    }

    @Test
    void reset_clears_the_key() {
        limiter.tryAcquire("email", 1, Duration.ofMinutes(15));
        assertThat(limiter.isBlocked("email", 1)).isTrue();
        limiter.reset("email");
        assertThat(limiter.isBlocked("email", 1)).isFalse();
    }
}

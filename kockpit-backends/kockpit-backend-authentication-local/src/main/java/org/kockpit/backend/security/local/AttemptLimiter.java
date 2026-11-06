package org.kockpit.backend.security.local;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counters per key (an IP, an email...), in memory: enough for a single backend
 * replica; each replica counts on its own otherwise.
 */
public class AttemptLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final Clock clock;

    public AttemptLimiter() {
        this(Clock.systemUTC());
    }

    AttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts one attempt; false (and not counted) when the key already reached max in the window. */
    public boolean tryAcquire(String key, int max, Duration window) {
        long now = clock.millis();
        cleanupIfLarge(now);
        boolean[] acquired = {false};
        windows.compute(key, (k, current) -> {
            Window w = current == null || current.expired(now) ? new Window(now + window.toMillis(), 0) : current;
            if (w.count() >= max) {
                return w;
            }
            acquired[0] = true;
            return new Window(w.endsAt(), w.count() + 1);
        });
        return acquired[0];
    }

    /** Whether the key reached max in its current window, without counting anything. */
    public boolean isBlocked(String key, int max) {
        Window w = windows.get(key);
        return w != null && !w.expired(clock.millis()) && w.count() >= max;
    }

    public void reset(String key) {
        windows.remove(key);
    }

    private void cleanupIfLarge(long now) {
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> w.expired(now));
        }
    }

    private record Window(long endsAt, int count) {
        boolean expired(long now) {
            return now >= endsAt;
        }
    }
}

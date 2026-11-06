package org.kockpit.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window counters per key, in memory (per gateway replica). */
public class RateLimiter {

    private record Window(Instant start, int count) {
    }

    private static final int MAX_KEYS = 100_000;

    private final Clock clock;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts one more event for {@code key}; false when {@code max} are already counted in the window. */
    public boolean tryAcquire(String key, int max, Duration window) {
        Instant now = clock.instant();
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> !e.getValue().start().plus(window).isAfter(now));
        }
        Window updated = windows.compute(key, (k, current) ->
                current == null || !current.start().plus(window).isAfter(now)
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        return updated.count() <= max;
    }

    public boolean isBlocked(String key, int max, Duration window) {
        Window current = windows.get(key);
        return current != null && current.start().plus(window).isAfter(clock.instant()) && current.count() >= max;
    }
}

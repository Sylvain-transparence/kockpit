package org.kockpit.gateway.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stops sending for a while after the gateway said so (429 over quota, 401 rejected key, gateway
 * down) instead of retrying every flush: what would have been sent meanwhile is dropped and
 * counted. Logs once when it closes and once when it opens again, with the count of dropped
 * items, so a quota reached at 10:00 means two log lines, not one per batch until midnight.
 */
public class SendGate {

    private static final Logger log = LoggerFactory.getLogger(SendGate.class);

    private final String what;

    private final Clock clock;

    private final AtomicLong dropped = new AtomicLong();

    private volatile Instant closedUntil = Instant.EPOCH;

    private volatile String reason;

    public SendGate(String what, Clock clock) {
        this.what = what;
        this.clock = clock;
    }

    public SendGate(String what) {
        this(what, Clock.systemUTC());
    }

    /** Whether sending is allowed; logs the reopening, with the count dropped while closed. */
    public synchronized boolean isOpen() {
        if (reason == null) {
            return true;
        }
        if (clock.instant().isBefore(closedUntil)) {
            return false;
        }
        long count = dropped.getAndSet(0);
        log.info("Kockpit gateway: sending {} again ({} dropped while paused: {})", what, count, reason);
        reason = null;
        return true;
    }

    /** Stops sending for {@code duration}; logged when not already closed. */
    public synchronized void close(Duration duration, String why) {
        Instant until = clock.instant().plus(duration);
        if (reason == null) {
            log.warn("⚠️ Kockpit gateway: {} paused until {} and dropped meanwhile: {}", what, until, why);
        }
        if (reason == null || until.isAfter(closedUntil)) {
            closedUntil = until;
        }
        reason = why;
    }

    public void drop(long count) {
        dropped.addAndGet(count);
    }

    /** Dropped since the gate closed (tests, metrics). */
    public long dropped() {
        return dropped.get();
    }
}

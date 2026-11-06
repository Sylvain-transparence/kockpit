package org.kockpit.audit.notification.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code kockpit.audit.notification.http.*}; the defaults match the gateway's limits. */
@ConfigurationProperties("kockpit.audit.notification.http")
public class HttpAuditProperties {

    /** Audits per request (the gateway's kockpit.gateway.audits.max-batch). */
    private int maxBatch = 500;

    /** Uncompressed bytes per request (the gateway accepts 1 MiB). */
    private int maxBodyBytes = 1_000_000;

    /** Retries of a request the gateway couldn't take (5xx, unreachable). */
    private int maxRetries = 2;

    /** Wait before the first retry, doubled for the second. */
    private Duration retryBackoff = Duration.ofSeconds(1);

    /** Audits dropped for this long after the retries failed. */
    private Duration unavailablePause = Duration.ofSeconds(30);

    /** Audits dropped for this long after the key was rejected (unknown, revoked). */
    private Duration rejectedKeyPause = Duration.ofMinutes(5);

    /** Refused audits (400, 413) logged as errors at most this often. */
    private Duration refusedLogInterval = Duration.ofMinutes(5);

    public int getMaxBatch() {
        return maxBatch;
    }

    public void setMaxBatch(int maxBatch) {
        this.maxBatch = maxBatch;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public Duration getUnavailablePause() {
        return unavailablePause;
    }

    public void setUnavailablePause(Duration unavailablePause) {
        this.unavailablePause = unavailablePause;
    }

    public Duration getRejectedKeyPause() {
        return rejectedKeyPause;
    }

    public void setRejectedKeyPause(Duration rejectedKeyPause) {
        this.rejectedKeyPause = rejectedKeyPause;
    }

    public Duration getRefusedLogInterval() {
        return refusedLogInterval;
    }

    public void setRefusedLogInterval(Duration refusedLogInterval) {
        this.refusedLogInterval = refusedLogInterval;
    }
}

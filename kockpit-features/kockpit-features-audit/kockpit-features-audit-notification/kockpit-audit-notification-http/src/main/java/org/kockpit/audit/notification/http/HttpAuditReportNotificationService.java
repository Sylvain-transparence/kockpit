package org.kockpit.audit.notification.http;

import org.kockpit.audit.api.AuditReportNotificationService;
import org.kockpit.audit.api.AuditReportWrapper;
import org.kockpit.gateway.client.GatewayClient;
import org.kockpit.gateway.client.GatewayResponse;
import org.kockpit.gateway.client.SendGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the buffered audits to the ingestion gateway ({@code POST /ingest/v1/audits}), as gzip
 * JSON arrays within the gateway's limits (count and uncompressed size per request).
 *
 * <p>The SDK compresses each audit on its own; they are uncompressed here, joined into one
 * array and compressed as a whole. The gateway sets the domain (the API key's tenant) and the
 * retention, checks the env against the tenant's envs and counts the audits against the key's
 * daily quota.
 *
 * <p>Audits are dropped, never retried forever, so an application never piles them up:
 * <ul>
 *   <li>429, over the daily quota: dropped until the quota resets (Retry-After), logged once;</li>
 *   <li>401/403, key unknown or revoked: dropped for {@code rejected-key-pause}, logged once;</li>
 *   <li>400/413, refused audits (an env not the tenant's, say): that request dropped, logged at
 *       most every {@code refused-log-interval};</li>
 *   <li>5xx or unreachable: retried {@code max-retries} times, then dropped and the sending
 *       paused for {@code unavailable-pause}.</li>
 * </ul>
 */
public class HttpAuditReportNotificationService implements AuditReportNotificationService {

    static final String PATH = "/ingest/v1/audits";

    private static final Logger log = LoggerFactory.getLogger(HttpAuditReportNotificationService.class);

    private final GatewayClient client;

    private final HttpAuditProperties properties;

    private final SendGate gate;

    private final Clock clock;

    private volatile Instant lastRefusedLog = Instant.EPOCH;

    public HttpAuditReportNotificationService(GatewayClient client, HttpAuditProperties properties, SendGate gate, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.gate = gate;
        this.clock = clock;
    }

    @Override
    public void notify(List<AuditReportWrapper> auditReports) {
        if (auditReports == null || auditReports.isEmpty()) {
            return;
        }
        if (!gate.isOpen()) {
            gate.drop(auditReports.size());
            return;
        }
        List<List<byte[]>> batches = batches(auditReports);
        for (int i = 0; i < batches.size(); i++) {
            if (!send(batches.get(i))) {
                long rest = batches.subList(i + 1, batches.size()).stream().mapToLong(List::size).sum();
                gate.drop(rest);
                return;
            }
        }
    }

    /** The audits as uncompressed JSON, in batches the gateway accepts. */
    List<List<byte[]>> batches(List<AuditReportWrapper> auditReports) {
        int maxBytes = properties.getMaxBodyBytes();
        List<List<byte[]>> batches = new ArrayList<>();
        List<byte[]> batch = new ArrayList<>();
        long batchBytes = 2; // [ ]
        for (AuditReportWrapper report : auditReports) {
            byte[] json = json(report, maxBytes);
            if (json == null) {
                continue;
            }
            if (!batch.isEmpty() && (batch.size() >= properties.getMaxBatch() || batchBytes + 1 + json.length > maxBytes)) {
                batches.add(batch);
                batch = new ArrayList<>();
                batchBytes = 2;
            }
            batchBytes += json.length + (batch.isEmpty() ? 0 : 1); // the comma before
            batch.add(json);
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }

    private byte[] json(AuditReportWrapper report, int maxBytes) {
        byte[] data = report.data();
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            byte[] json = GatewayClient.isGzip(data) ? GatewayClient.gunzip(data, maxBytes) : data;
            if (json.length + 2 > maxBytes) {
                log.error("Audit {} of {} is larger than the gateway's {} bytes: dropped", report.id(), report.appId(), maxBytes);
                return null;
            }
            return json;
        } catch (IOException e) {
            log.error("Audit {} of {} can't be uncompressed: dropped", report.id(), report.appId(), e);
            return null;
        }
    }

    /** Whether to go on with the next batches. */
    private boolean send(List<byte[]> batch) {
        byte[] body = array(batch);
        int attempt = 0;
        while (true) {
            String failure;
            try {
                GatewayResponse response = client.postJson(PATH, body, true);
                if (response.isSuccess()) {
                    log.trace("{} audits accepted by the Kockpit gateway, {} left today", batch.size(),
                            response.header("X-Quota-Remaining").orElse("?"));
                    return true;
                }
                String reason = GatewayClient.errorMessage(response).orElse("HTTP " + response.status());
                switch (response.status()) {
                    case 429 -> {
                        gate.close(response.retryAfter().orElse(Duration.ofMinutes(5)), "daily audit quota reached: " + reason);
                        gate.drop(batch.size());
                        return false;
                    }
                    case 401, 403 -> {
                        gate.close(properties.getRejectedKeyPause(), "API key rejected: " + reason);
                        gate.drop(batch.size());
                        return false;
                    }
                    case 400, 413, 415 -> {
                        logRefused(batch.size(), response.status(), reason);
                        return true;
                    }
                    default -> failure = "HTTP %d: %s".formatted(response.status(), reason);
                }
            } catch (IOException e) {
                failure = e.toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted: {} audits not sent to the Kockpit gateway", batch.size());
                return false;
            }
            if (attempt >= properties.getMaxRetries()) {
                gate.close(properties.getUnavailablePause(), "gateway %s unavailable (%s)".formatted(client.baseUri(), failure));
                gate.drop(batch.size());
                return false;
            }
            attempt++;
            if (!sleep(properties.getRetryBackoff().multipliedBy(attempt))) {
                return false;
            }
        }
    }

    private void logRefused(int count, int status, String reason) {
        Instant now = clock.instant();
        if (now.isAfter(lastRefusedLog.plus(properties.getRefusedLogInterval()))) {
            lastRefusedLog = now;
            log.error("Kockpit gateway refused {} audits (HTTP {}), dropped: {}", count, status, reason);
        } else {
            log.debug("Kockpit gateway refused {} audits (HTTP {}), dropped: {}", count, status, reason);
        }
    }

    private static byte[] array(List<byte[]> batch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(batch.stream().mapToInt(json -> json.length + 1).sum() + 2);
        out.write('[');
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.writeBytes(batch.get(i));
        }
        out.write(']');
        return out.toByteArray();
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

package org.kockpit.gateway;

import lombok.extern.slf4j.Slf4j;
import org.kockpit.gateway.ApiKeyStore.ApiKey;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Checks a batch of audits (the SDK's AuditReport JSON), rewrites what the key decides, counts
 * the batch against the key's daily quota, then hands it to the audit stream.
 * <ul>
 *   <li>{@code domain}: always the key's tenant, whatever the client sent;</li>
 *   <li>{@code env}: required, one of the tenant's envs;</li>
 *   <li>{@code ttl}: always {@code kockpit.gateway.audits.ttl-days} - it is part of the index
 *       name and retention policy, a client choosing it would multiply indices;</li>
 *   <li>{@code appId} and {@code id} (generated when missing): plain names;</li>
 *   <li>everything else is passed through as sent.</li>
 * </ul>
 * All or nothing: one invalid audit, or a batch that doesn't fit in what remains of the quota,
 * refuses the whole batch and counts nothing.
 */
@Component
@Slf4j
public class AuditIngestion {

    public record Accepted(int accepted, long remaining, int quota) {
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final UsageRepository usage;

    private final AuditSink sink;

    private final GatewayProperties.Audits properties;

    private final Clock clock;

    public AuditIngestion(UsageRepository usage, AuditSink sink, GatewayProperties properties, Clock clock) {
        this.usage = usage;
        this.sink = sink;
        this.properties = properties.getAudits();
        this.clock = clock;
    }

    public Accepted ingest(ApiKey key, byte[] body) {
        List<AuditSink.Record> records = records(key, parse(body));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        OptionalLong total = usage.tryConsume(key.id(), today, records.size(), key.dailyQuota());
        if (total.isEmpty()) {
            long remaining = Math.max(0, key.dailyQuota() - usage.used(key.id(), today));
            throw overQuota(key, records.size(), remaining);
        }
        try {
            sink.send(records);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            usage.refund(key.id(), today, records.size());
            log.error("Could not deliver {} audits of {} (key {})", records.size(), key.domain(), key.prefix(), e);
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                    "The audits could not be delivered, nothing was counted: retry later");
        }
        usage.touch(key.id());
        return new Accepted(records.size(), key.dailyQuota() - total.getAsLong(), key.dailyQuota());
    }

    private List<JsonNode> parse(byte[] body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw GatewayException.badRequest("The body isn't valid JSON");
        }
        List<JsonNode> audits = new ArrayList<>();
        if (root != null && root.isArray()) {
            root.forEach(audits::add);
        } else if (root != null && root.isObject()) {
            audits.add(root);
        } else {
            throw GatewayException.badRequest("Send an audit (JSON object) or an array of audits");
        }
        if (audits.isEmpty()) {
            throw GatewayException.badRequest("No audit in the batch");
        }
        if (audits.size() > properties.getMaxBatch()) {
            throw GatewayException.badRequest("%d audits per request at most".formatted(properties.getMaxBatch()));
        }
        return audits;
    }

    private List<AuditSink.Record> records(ApiKey key, List<JsonNode> audits) {
        List<AuditSink.Record> records = new ArrayList<>(audits.size());
        for (int i = 0; i < audits.size(); i++) {
            if (!(audits.get(i) instanceof ObjectNode audit)) {
                throw invalid(i, "not a JSON object");
            }
            String env = text(audit, "env");
            if (env == null || !key.envs().contains(env)) {
                throw invalid(i, "env %s isn't one of the tenant's envs %s".formatted(env, new TreeSet<>(key.envs())));
            }
            String appId = text(audit, "appId");
            if (!Names.isValid(appId)) {
                throw invalid(i, "appId is required: up to 100 letters, digits, '.', '_' or '-'");
            }
            String id = text(audit, "id");
            if (id == null) {
                audit.put("id", UUID.randomUUID().toString());
            } else if (!Names.isValid(id)) {
                throw invalid(i, "id may only hold up to 100 letters, digits, '.', '_' or '-'");
            }
            audit.put("domain", key.domain());
            audit.put("ttl", properties.getTtlDays());
            records.add(new AuditSink.Record("%s-%s-%s".formatted(key.domain(), env, appId), MAPPER.writeValueAsBytes(audit)));
        }
        return records;
    }

    private static String text(ObjectNode audit, String field) {
        JsonNode value = audit.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static GatewayException invalid(int index, String reason) {
        return GatewayException.badRequest("Audit #%d: %s".formatted(index, reason));
    }

    private GatewayException overQuota(ApiKey key, int batch, long remaining) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        long untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC)).toSeconds();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, untilMidnight)));
        headers.set(IngestApi.QUOTA_LIMIT, String.valueOf(key.dailyQuota()));
        headers.set(IngestApi.QUOTA_REMAINING, String.valueOf(remaining));
        String message = remaining == 0
                ? "Daily quota of %d audits reached, retry after midnight UTC".formatted(key.dailyQuota())
                : "A batch of %d audits exceeds the %d left today: send fewer".formatted(batch, remaining);
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", message, headers);
    }
}

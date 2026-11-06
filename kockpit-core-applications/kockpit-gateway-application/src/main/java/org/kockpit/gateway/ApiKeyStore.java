package org.kockpit.gateway;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API keys of the tenants (tables of the backend's kockpit-backend-tenants), looked up by their
 * SHA-256 and cached {@code kockpit.gateway.keys.cache-ttl}: a revocation or a quota change
 * applies within that delay. Unknown keys aren't cached (the failed-key limiter bounds them).
 */
@Component
public class ApiKeyStore {

    /** What a valid key grants: its tenant's domain and envs, and its daily quota. */
    public record ApiKey(UUID id, String prefix, String domain, Set<String> envs, int dailyQuota) {
    }

    private record Cached(Optional<ApiKey> key, Instant expiresAt) {
    }

    private static final int MAX_CACHED = 10_000;

    private final JdbcClient jdbc;

    private final Duration ttl;

    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ApiKeyStore(JdbcClient jdbc, GatewayProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.ttl = properties.getKeys().getCacheTtl();
        this.clock = clock;
    }

    /** The active key, or empty when unknown or revoked. */
    public Optional<ApiKey> find(String key) {
        if (key == null || !key.startsWith("kpk_") || key.length() > 100) {
            return Optional.empty();
        }
        String hash = sha256(key);
        Instant now = clock.instant();
        Cached cached = cache.get(hash);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.key();
        }
        Optional<ApiKey> found = load(hash);
        if (found.isPresent()) {
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(hash, new Cached(found, now.plus(ttl)));
        } else {
            // a key that just got revoked must not stay usable from the cache
            cache.remove(hash);
        }
        return found;
    }

    private record Row(UUID id, String prefix, int dailyQuota, UUID tenantId, String slug) {
    }

    private Optional<ApiKey> load(String hash) {
        // two queries, not one nested in the other's row mapper: that would hold two pooled
        // connections per lookup and starve the pool under concurrent requests
        return jdbc.sql("""
                        select k.id, k.prefix, k.daily_quota, t.id as tenant_id, t.slug
                        from api_keys k join tenants t on t.id = k.tenant_id
                        where k.key_hash = :hash and k.revoked_at is null""")
                .param("hash", hash)
                .query((rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("prefix"),
                        rs.getInt("daily_quota"), rs.getObject("tenant_id", UUID.class), rs.getString("slug")))
                .optional()
                .map(row -> new ApiKey(row.id(), row.prefix(), row.slug(), Set.copyOf(envs(row.tenantId())), row.dailyQuota()));
    }

    private List<String> envs(UUID tenantId) {
        return jdbc.sql("select env from tenant_envs where tenant_id = :id")
                .param("id", tenantId).query(String.class).list();
    }

    static String sha256(String key) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package org.kockpit.backend.tenants;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenants, their envs and API keys (tables of db/kockpit-accounts V2). */
public class TenantRepository {

    public record Tenant(UUID id, String slug, UUID ownerId, Instant createdAt) {
    }

    public record ApiKey(UUID id, UUID tenantId, String name, String prefix, int dailyQuota,
                         Instant createdAt, Instant lastUsedAt, Instant revokedAt, long usedToday) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    private static final String TENANT_COLUMNS = "id, slug, owner_id, created_at";

    private static final String KEY_COLUMNS = """
            k.id, k.tenant_id, k.name, k.prefix, k.daily_quota, k.created_at, k.last_used_at, k.revoked_at,
            coalesce(u.audits, 0) as used_today""";

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the user's row until the end of the transaction: serializes their tenant creations. */
    public void lockUser(UUID userId) {
        jdbc.sql("select id from users where id = :id for update").param("id", userId).query(UUID.class).optional();
    }

    /** Locks the tenant's row until the end of the transaction: serializes its key creations. */
    public void lockTenant(UUID tenantId) {
        jdbc.sql("select id from tenants where id = :id for update").param("id", tenantId).query(UUID.class).optional();
    }

    public int countByOwner(UUID ownerId) {
        return jdbc.sql("select count(*) from tenants where owner_id = :owner")
                .param("owner", ownerId).query(Integer.class).single();
    }

    /** Creates the tenant, or returns empty when the slug is taken. */
    public Optional<Tenant> create(String slug, UUID ownerId, List<String> envs) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into tenants (id, slug, owner_id) values (:id, :slug, :owner)")
                    .param("id", id).param("slug", slug).param("owner", ownerId).update();
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        for (String env : envs) {
            jdbc.sql("insert into tenant_envs (tenant_id, env) values (:id, :env)")
                    .param("id", id).param("env", env).update();
        }
        return findById(id);
    }

    public void delete(UUID tenantId) {
        jdbc.sql("delete from tenants where id = :id").param("id", tenantId).update();
    }

    public Optional<Tenant> findById(UUID id) {
        return jdbc.sql("select " + TENANT_COLUMNS + " from tenants where id = :id")
                .param("id", id).query(this::tenant).optional();
    }

    public Optional<Tenant> findBySlug(String slug) {
        return jdbc.sql("select " + TENANT_COLUMNS + " from tenants where slug = :slug")
                .param("slug", slug).query(this::tenant).optional();
    }

    /** The tenants the user owns or, through its group, is a member of. */
    public List<Tenant> findForUser(UUID userId) {
        return jdbc.sql("""
                        select %s from tenants t
                        where t.owner_id = :user
                           or exists (select 1 from user_groups g
                                      where g.user_id = :user and g.group_name = 'tenant:' || t.slug)
                        order by t.created_at""".formatted("t.id, t.slug, t.owner_id, t.created_at"))
                .param("user", userId).query(this::tenant).list();
    }

    public List<Tenant> findAll() {
        return jdbc.sql("select " + TENANT_COLUMNS + " from tenants order by created_at")
                .query(this::tenant).list();
    }

    public List<String> envs(UUID tenantId) {
        return jdbc.sql("select env from tenant_envs where tenant_id = :id order by env")
                .param("id", tenantId).query(String.class).list();
    }

    public String ownerEmail(UUID ownerId) {
        return jdbc.sql("select email from users where id = :id").param("id", ownerId)
                .query(String.class).optional().orElse(null);
    }

    public int countActiveKeys(UUID tenantId) {
        return jdbc.sql("select count(*) from api_keys where tenant_id = :id and revoked_at is null")
                .param("id", tenantId).query(Integer.class).single();
    }

    public UUID createKey(UUID tenantId, String name, String prefix, String keyHash, int dailyQuota) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into api_keys (id, tenant_id, name, prefix, key_hash, daily_quota)
                        values (:id, :tenant, :name, :prefix, :hash, :quota)""")
                .param("id", id).param("tenant", tenantId).param("name", name).param("prefix", prefix)
                .param("hash", keyHash).param("quota", dailyQuota)
                .update();
        return id;
    }

    /** The tenant's keys, revoked ones included, with what they sent on {@code day}. */
    public List<ApiKey> keys(UUID tenantId, LocalDate day) {
        return jdbc.sql("""
                        select %s from api_keys k
                        left join usage_daily u on u.api_key_id = k.id and u.day = :day
                        where k.tenant_id = :tenant
                        order by k.created_at""".formatted(KEY_COLUMNS))
                .param("tenant", tenantId).param("day", day).query(this::apiKey).list();
    }

    public Optional<ApiKey> findKey(UUID keyId, LocalDate day) {
        return jdbc.sql("""
                        select %s from api_keys k
                        left join usage_daily u on u.api_key_id = k.id and u.day = :day
                        where k.id = :id""".formatted(KEY_COLUMNS))
                .param("id", keyId).param("day", day).query(this::apiKey).optional();
    }

    /** Revokes the key (kept, for its usage history); false when it doesn't exist or already is. */
    public boolean revokeKey(UUID keyId) {
        return jdbc.sql("update api_keys set revoked_at = now() where id = :id and revoked_at is null")
                .param("id", keyId).update() == 1;
    }

    public boolean setKeyQuota(UUID keyId, int dailyQuota) {
        return jdbc.sql("update api_keys set daily_quota = :quota where id = :id")
                .param("id", keyId).param("quota", dailyQuota).update() == 1;
    }

    private Tenant tenant(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Tenant(rs.getObject("id", UUID.class), rs.getString("slug"),
                rs.getObject("owner_id", UUID.class), instant(rs, "created_at"));
    }

    private ApiKey apiKey(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ApiKey(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("name"), rs.getString("prefix"), rs.getInt("daily_quota"),
                instant(rs, "created_at"), instant(rs, "last_used_at"), instant(rs, "revoked_at"),
                rs.getLong("used_today"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

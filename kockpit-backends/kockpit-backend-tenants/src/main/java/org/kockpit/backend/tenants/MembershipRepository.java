package org.kockpit.backend.tenants;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant members and invitations (db/kockpit-accounts V1 user_groups, V3 tenant_invitations).
 * A member is an account in the tenant's group {@code tenant:<slug>}, the owner included.
 */
public class MembershipRepository {

    public record Member(UUID userId, String email, Instant joinedAt) {
    }

    public record Invitation(UUID id, UUID tenantId, String email, UUID createdBy, Instant createdAt,
                             Instant expiresAt, UUID acceptedBy, Instant acceptedAt, Instant revokedAt) {

        /** Neither accepted, revoked nor expired at {@code now}. */
        public boolean isOpen(Instant now) {
            return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
        }
    }

    private static final String INVITATION_COLUMNS =
            "id, tenant_id, email, created_by, created_at, expires_at, accepted_by, accepted_at, revoked_at";

    private final JdbcClient jdbc;

    public MembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The accounts in the tenant's group, in the order they joined: the invitation they accepted,
     * else the tenant's creation (its owner).
     */
    public List<Member> members(UUID tenantId, String group) {
        return jdbc.sql("""
                        select u.id, u.email,
                               coalesce((select max(i.accepted_at) from tenant_invitations i
                                         where i.tenant_id = :tenant and i.accepted_by = u.id),
                                        (select t.created_at from tenants t where t.id = :tenant)) as joined_at
                        from user_groups g
                        join users u on u.id = g.user_id
                        where g.group_name = :group
                        order by joined_at, u.email""")
                .param("tenant", tenantId)
                .param("group", group)
                .query((rs, row) -> new Member(rs.getObject("id", UUID.class), rs.getString("email"), instant(rs, "joined_at")))
                .list();
    }

    public int countMembers(String group) {
        return jdbc.sql("select count(*) from user_groups where group_name = :group")
                .param("group", group).query(Integer.class).single();
    }

    public UUID createInvitation(UUID tenantId, String codeHash, String email, UUID createdBy, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into tenant_invitations (id, tenant_id, code_hash, email, created_by, expires_at)
                        values (:id, :tenant, :hash, :email, :by, :expires)""")
                .param("id", id).param("tenant", tenantId).param("hash", codeHash).param("email", email)
                .param("by", createdBy).param("expires", OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC))
                .update();
        return id;
    }

    public Optional<Invitation> findInvitation(UUID id) {
        return jdbc.sql("select " + INVITATION_COLUMNS + " from tenant_invitations where id = :id")
                .param("id", id).query(this::invitation).optional();
    }

    /** By code, locked until the end of the transaction: one acceptance per invitation. */
    public Optional<Invitation> findByCodeForUpdate(String codeHash) {
        return jdbc.sql("select " + INVITATION_COLUMNS + " from tenant_invitations where code_hash = :hash for update")
                .param("hash", codeHash).query(this::invitation).optional();
    }

    public Optional<Invitation> findByCode(String codeHash) {
        return jdbc.sql("select " + INVITATION_COLUMNS + " from tenant_invitations where code_hash = :hash")
                .param("hash", codeHash).query(this::invitation).optional();
    }

    /** The tenant's invitations not yet accepted, revoked or expired. */
    public List<Invitation> openInvitations(UUID tenantId) {
        return jdbc.sql("""
                        select %s from tenant_invitations
                        where tenant_id = :tenant and accepted_at is null and revoked_at is null and expires_at > now()
                        order by created_at""".formatted(INVITATION_COLUMNS))
                .param("tenant", tenantId).query(this::invitation).list();
    }

    public int countOpenInvitations(UUID tenantId) {
        return jdbc.sql("""
                        select count(*) from tenant_invitations
                        where tenant_id = :tenant and accepted_at is null and revoked_at is null and expires_at > now()""")
                .param("tenant", tenantId).query(Integer.class).single();
    }

    public void markAccepted(UUID invitationId, UUID userId) {
        jdbc.sql("update tenant_invitations set accepted_by = :user, accepted_at = now() where id = :id")
                .param("id", invitationId).param("user", userId).update();
    }

    /** False when it doesn't exist or is no longer open. */
    public boolean revokeInvitation(UUID invitationId) {
        return jdbc.sql("""
                        update tenant_invitations set revoked_at = now()
                        where id = :id and accepted_at is null and revoked_at is null""")
                .param("id", invitationId).update() == 1;
    }

    private Invitation invitation(ResultSet rs, int row) throws SQLException {
        return new Invitation(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("email"), rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                instant(rs, "expires_at"), rs.getObject("accepted_by", UUID.class), instant(rs, "accepted_at"),
                instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

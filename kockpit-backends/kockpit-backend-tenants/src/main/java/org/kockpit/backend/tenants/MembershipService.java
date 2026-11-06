package org.kockpit.backend.tenants;

import lombok.extern.slf4j.Slf4j;
import org.kockpit.backend.security.local.AttemptLimiter;
import org.kockpit.backend.security.local.LocalUser;
import org.kockpit.backend.security.local.UserRepository;
import org.kockpit.backend.tenants.MembershipRepository.Invitation;
import org.kockpit.backend.tenants.MembershipRepository.Member;
import org.kockpit.backend.tenants.TenantRepository.Tenant;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who belongs to a tenant, and invitations to join one.
 *
 * <p>A member is an account in the tenant's group {@code tenant:<slug>}: it sees the tenant's
 * audits and keys (prefixes, usage). The owner (who created it) and administrators also create and
 * revoke keys, invite and remove members. Groups are read from the database on each request, so
 * joining or leaving takes effect at once, without a new login.
 *
 * <p>An invitation is a one-time code, stored hashed, valid {@code invitation-ttl}, optionally
 * for one email only; the owner sends its link himself (Kockpit sends no mail). Joining a tenant
 * doesn't count against the tenants a user may create.
 */
@Slf4j
public class MembershipService {

    static final String CODE_PREFIX = "kpi_";

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private static final Duration HOUR = Duration.ofHours(1);

    // failed acceptances per account and hour: codes can't be guessed (256 bits), but no need to let anyone try
    private static final int MAX_FAILED_ACCEPTS_PER_HOUR = 20;

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final TransactionTemplate transaction;
    private final TenantProperties properties;
    private final AttemptLimiter limiter;
    private final Clock clock;

    public record CreatedInvitation(Invitation invitation, String code) {
    }

    /** What the invited account sees before accepting. */
    public record Preview(Tenant tenant, String invitedBy, Instant expiresAt, boolean alreadyMember) {
    }

    public MembershipService(TenantRepository tenants, MembershipRepository memberships, UserRepository users,
                             TransactionTemplate transaction, TenantProperties properties, AttemptLimiter limiter,
                             Clock clock) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.users = users;
        this.transaction = transaction;
        this.properties = properties;
        this.limiter = limiter;
        this.clock = clock;
    }

    public boolean isOwner(Tenant tenant, UUID userId) {
        return tenant.ownerId().equals(userId);
    }

    /** The tenant, if the user is one of its members or an administrator; else 404, as if it didn't exist. */
    public Tenant visible(UUID userId, boolean admin, String slug) {
        Optional<Tenant> tenant = TenantNames.isValidSlug(slug) ? tenants.findBySlug(slug) : Optional.empty();
        return tenant
                .filter(t -> admin || isOwner(t, userId) || users.groups(userId).contains(TenantNames.group(t.slug())))
                .orElseThrow(() -> TenantException.notFound("No tenant " + slug));
    }

    /** The tenant, if the user owns it or is an administrator; 403 for its other members, 404 for anyone else. */
    public Tenant owned(UUID userId, boolean admin, String slug) {
        Tenant tenant = visible(userId, admin, slug);
        if (!admin && !isOwner(tenant, userId)) {
            throw TenantException.forbidden("Only the tenant's owner can do this");
        }
        return tenant;
    }

    public List<Member> members(Tenant tenant) {
        return memberships.members(tenant.id(), TenantNames.group(tenant.slug()));
    }

    public CreatedInvitation invite(Tenant tenant, UUID createdBy, String email) {
        String restrictedTo = normalizeEmail(email);
        if (restrictedTo != null && !EMAIL.matcher(restrictedTo).matches()) {
            throw TenantException.badRequest("Invalid email");
        }
        String code = ApiKeys.generate(CODE_PREFIX);
        UUID id = transaction.execute(status -> {
            tenants.lockTenant(tenant.id());
            if (memberships.countOpenInvitations(tenant.id()) >= properties.getMaxOpenInvitations()) {
                throw TenantException.conflict("invitation_limit",
                        "%d open invitations at most: cancel one first".formatted(properties.getMaxOpenInvitations()));
            }
            if (memberships.countMembers(TenantNames.group(tenant.slug())) >= properties.getMaxMembers()) {
                throw TenantException.conflict("member_limit",
                        "A tenant has %d members at most".formatted(properties.getMaxMembers()));
            }
            return memberships.createInvitation(tenant.id(), ApiKeys.hash(code), restrictedTo, createdBy,
                    clock.instant().plus(properties.getInvitationTtl()));
        });
        log.info("Invitation {} to tenant {} created{}", id, tenant.slug(), restrictedTo == null ? "" : " for " + restrictedTo);
        return new CreatedInvitation(memberships.findInvitation(id).orElseThrow(), code);
    }

    public List<Invitation> openInvitations(Tenant tenant) {
        return memberships.openInvitations(tenant.id());
    }

    public void revokeInvitation(Tenant tenant, UUID invitationId) {
        Invitation invitation = memberships.findInvitation(invitationId)
                .filter(i -> i.tenantId().equals(tenant.id()))
                .orElseThrow(() -> TenantException.notFound("No invitation " + invitationId));
        if (memberships.revokeInvitation(invitation.id())) {
            log.info("Invitation {} to tenant {} cancelled", invitation.id(), tenant.slug());
        }
    }

    public Preview preview(String code, UUID userId) {
        LocalUser user = user(userId);
        Invitation invitation = usable(code, user, memberships.findByCode(hashOf(code)));
        Tenant tenant = tenants.findById(invitation.tenantId()).orElseThrow(TenantException::invalidInvitation);
        boolean member = users.groups(userId).contains(TenantNames.group(tenant.slug()));
        return new Preview(tenant, invitation.createdBy() == null ? null : tenants.ownerEmail(invitation.createdBy()),
                invitation.expiresAt(), member);
    }

    /** Joins the tenant; accepting a tenant one already belongs to changes nothing and keeps the invitation. */
    public Tenant accept(String code, UUID userId) {
        LocalUser user = user(userId);
        Tenant joined = transaction.execute(status -> {
            Invitation invitation = usable(code, user, memberships.findByCodeForUpdate(hashOf(code)));
            Tenant tenant = tenants.findById(invitation.tenantId()).orElseThrow(TenantException::invalidInvitation);
            String group = TenantNames.group(tenant.slug());
            if (users.groups(userId).contains(group)) {
                return tenant;
            }
            tenants.lockTenant(tenant.id());
            if (memberships.countMembers(group) >= properties.getMaxMembers()) {
                throw TenantException.conflict("member_limit",
                        "Tenant %s has %d members already".formatted(tenant.slug(), properties.getMaxMembers()));
            }
            users.addGroup(userId, group);
            memberships.markAccepted(invitation.id(), userId);
            log.info("User {} joined tenant {} (invitation {})", user.email(), tenant.slug(), invitation.id());
            return tenant;
        });
        limiter.reset(failuresKey(userId));
        return joined;
    }

    /**
     * Removes a member: the owner and administrators remove anyone but the owner; a member may
     * remove himself (leave). The owner can't leave his own tenant.
     */
    public void removeMember(Tenant tenant, UUID actorId, boolean admin, UUID memberId) {
        boolean self = actorId.equals(memberId);
        if (!self && !admin && !isOwner(tenant, actorId)) {
            throw TenantException.forbidden("Only the tenant's owner can remove its members");
        }
        if (isOwner(tenant, memberId)) {
            throw TenantException.conflict("owner", "The tenant's owner can't be removed");
        }
        String group = TenantNames.group(tenant.slug());
        if (!users.groups(memberId).contains(group)) {
            throw TenantException.notFound("No member " + memberId);
        }
        users.removeGroup(memberId, group);
        log.info("User {} {} tenant {}", memberId, self ? "left" : "removed from", tenant.slug());
    }

    // the invitation, if this account may accept it now; one answer for unknown, used, cancelled and expired
    private Invitation usable(String code, LocalUser user, Optional<Invitation> found) {
        String key = failuresKey(user.id());
        if (limiter.isBlocked(key, MAX_FAILED_ACCEPTS_PER_HOUR)) {
            throw new TenantException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", "Too many attempts, try again later");
        }
        Optional<Invitation> invitation = found.filter(i -> i.isOpen(clock.instant()));
        if (invitation.isEmpty()) {
            limiter.tryAcquire(key, Integer.MAX_VALUE, HOUR);
            throw TenantException.invalidInvitation();
        }
        String restrictedTo = invitation.get().email();
        if (restrictedTo != null && !restrictedTo.equals(normalizeEmail(user.email()))) {
            limiter.tryAcquire(key, Integer.MAX_VALUE, HOUR);
            // which address it is for stays with the owner
            throw TenantException.forbidden("This invitation is for another account: sign in with the invited email");
        }
        return invitation.get();
    }

    private LocalUser user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> TenantException.forbidden("Unknown account"));
    }

    private static String hashOf(String code) {
        if (code == null || !code.startsWith(CODE_PREFIX) || code.length() > 100) {
            return ApiKeys.hash("");
        }
        return ApiKeys.hash(code);
    }

    private static String failuresKey(UUID userId) {
        return "invitation-failures:" + userId;
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

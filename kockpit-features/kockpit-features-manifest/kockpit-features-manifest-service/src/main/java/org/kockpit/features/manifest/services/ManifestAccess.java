package org.kockpit.features.manifest.services;

import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.kockpit.features.manifest.services.dto.PolicyDto;
import org.kockpit.features.manifest.services.dto.ServiceDto;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Who may see what in a manifest, given the user's groups ("custom:adgroups").
 *
 * <ul>
 *   <li>A manifest declaring neither {@code groups} nor {@code policies} is unrestricted.</li>
 *   <li>A member of one of the manifest's {@code groups} sees all of it.</li>
 *   <li>Otherwise only the policies listing one of the user's groups apply; their
 *       {@code resources} grant services: {@code "*"} (everything), {@code "audit:*"} (every
 *       service of a type) or {@code "audit:offers-api"} (one service).</li>
 *   <li>Older policies grant through {@code permissions} instead ({@code "audit:read"}, with
 *       empty {@code resources} meaning every service of that type) - still honoured.</li>
 *   <li>Group names match case-insensitively, and the group {@code "*"} matches every user
 *       (e.g. {@code {"groups":["*"],"resources":["audit:*"]}}: audits open to all) - as the
 *       legacy console did.</li>
 * </ul>
 */
public final class ManifestAccess {

    private static final String WILDCARD = "*";

    private ManifestAccess() {
    }

    public static boolean isRestricted(ManifestDto manifest) {
        return notEmpty(manifest.getGroups()) || notEmpty(manifest.getPolicies());
    }

    /** Whether the manifest shows up at all (menus / domain-env selector). */
    public static boolean canSee(ManifestDto manifest, Set<String> userGroups) {
        return !isRestricted(manifest)
                || isManifestMember(manifest, userGroups)
                || !matchingPolicies(manifest, userGroups).isEmpty();
    }

    /**
     * Whether the user may use services of {@code type} - one service ({@code id}), or, when
     * {@code id} is null (endpoints not tied to one service, e.g. audit search), at least one.
     */
    public static boolean canAccess(ManifestDto manifest, Set<String> userGroups, String type, String id) {
        if (!isRestricted(manifest) || isManifestMember(manifest, userGroups)) {
            return true;
        }
        return matchingPolicies(manifest, userGroups).stream()
                .anyMatch(policy -> grants(policy, type, id));
    }

    /** The manifest as this user may see it: same manifest, services filtered. */
    public static ManifestDto visibleTo(ManifestDto manifest, Set<String> userGroups) {
        if (!isRestricted(manifest) || isManifestMember(manifest, userGroups)) {
            return manifest;
        }
        List<PolicyDto> policies = matchingPolicies(manifest, userGroups);
        List<ServiceDto> services = manifest.getServices() == null ? List.of() :
                manifest.getServices().stream()
                        .filter(service -> policies.stream().anyMatch(p -> grants(p, service.getType(), service.getId())))
                        .toList();
        return ManifestDto.builder()
                .domain(manifest.getDomain())
                .env(manifest.getEnv())
                .name(manifest.getName())
                .services(services)
                .groups(manifest.getGroups())
                .policies(manifest.getPolicies())
                .build();
    }

    private static boolean isManifestMember(ManifestDto manifest, Set<String> userGroups) {
        return intersects(manifest.getGroups(), userGroups);
    }

    private static List<PolicyDto> matchingPolicies(ManifestDto manifest, Set<String> userGroups) {
        if (manifest.getPolicies() == null) {
            return List.of();
        }
        return manifest.getPolicies().stream()
                .filter(policy -> intersects(policy.getGroups(), userGroups))
                .toList();
    }

    private static boolean grants(PolicyDto policy, String type, String id) {
        if (notEmpty(policy.getResources())) {
            return policy.getResources().stream().anyMatch(resource -> matches(resource, type, id));
        }
        // Legacy shape: permissions "type:action" and no resources = all services of that type.
        return policy.getPermissions() != null && policy.getPermissions().stream()
                .anyMatch(permission -> permission.equals(WILDCARD) || permission.startsWith(type + ":"));
    }

    private static boolean matches(String resource, String type, String id) {
        if (WILDCARD.equals(resource)) {
            return true;
        }
        int colon = resource.indexOf(':');
        if (colon < 0 || !resource.substring(0, colon).equals(type)) {
            return false;
        }
        String resourceId = resource.substring(colon + 1);
        // id == null: the caller only needs some service of this type - any grant on the type counts.
        return WILDCARD.equals(resourceId) || id == null || resourceId.equals(id);
    }

    private static boolean intersects(Collection<String> groups, Set<String> userGroups) {
        if (groups == null || userGroups == null) {
            return false;
        }
        return groups.stream().anyMatch(group -> WILDCARD.equals(group)
                || userGroups.stream().anyMatch(group::equalsIgnoreCase));
    }

    private static boolean notEmpty(Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }
}

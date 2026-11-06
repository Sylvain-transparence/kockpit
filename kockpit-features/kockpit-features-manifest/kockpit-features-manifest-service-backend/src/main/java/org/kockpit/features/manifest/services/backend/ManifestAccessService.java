package org.kockpit.features.manifest.services.backend;

import lombok.extern.slf4j.Slf4j;
import org.kockpit.features.manifest.services.ManifestAccess;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies {@link ManifestAccess} to the authenticated user. The user's groups are the request's
 * authorities, mapped from the token's "custom:adgroups" by the security configuration.
 *
 * <p>Access control is on when {@code kockpit.security.access-control.enabled} is true, or, when
 * that property isn't set, when Cognito authentication is configured
 * ({@code kockpit.security.cognito.issuer-uri}): without a trusted identity (local runs, basic
 * auth) everything stays visible as before.
 *
 * <p>With access control on:
 * <ul>
 *   <li>members of {@code kockpit.security.access-control.admin-groups} see and may change
 *       everything;</li>
 *   <li>with {@code kockpit.security.access-control.hide-unrestricted}, a manifest declaring
 *       neither groups nor policies is visible to admins only, instead of to everyone - for a
 *       shared instance where an unrestricted manifest would expose its audits to every user;</li>
 *   <li>saving a manifest is limited to its owners, see {@link #whySaveDenied}.</li>
 * </ul>
 */
@Component
@Slf4j
public class ManifestAccessService {

    private static final String WILDCARD = "*";

    private final ManifestBackendService manifestBackendService;

    private final boolean enabled;

    private final Set<String> adminGroups;

    private final boolean hideUnrestricted;

    @Autowired
    public ManifestAccessService(
            ManifestBackendService manifestBackendService,
            @Value("${kockpit.security.cognito.issuer-uri:}") String cognitoIssuerUri,
            @Value("${kockpit.security.access-control.enabled:}") String accessControlEnabled,
            @Value("${kockpit.security.access-control.admin-groups:}") String adminGroups,
            @Value("${kockpit.security.access-control.hide-unrestricted:false}") boolean hideUnrestricted
    ) {
        this.manifestBackendService = manifestBackendService;
        this.enabled = StringUtils.hasText(accessControlEnabled)
                ? Boolean.parseBoolean(accessControlEnabled.trim())
                : StringUtils.hasText(cognitoIssuerUri);
        this.adminGroups = parseGroups(adminGroups);
        this.hideUnrestricted = hideUnrestricted;
        if (enabled) {
            log.info("Manifest access control by user groups: enabled, admin groups {}, unrestricted manifests {}",
                    this.adminGroups, hideUnrestricted ? "hidden (admins only)" : "visible to everyone");
        } else {
            log.info("Manifest access control by user groups: disabled");
        }
    }

    public ManifestAccessService(ManifestBackendService manifestBackendService, String cognitoIssuerUri) {
        this(manifestBackendService, cognitoIssuerUri, "", "", false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<ManifestDto> visibleManifests(Authentication authentication) {
        List<ManifestDto> manifests = manifestBackendService.list();
        Set<String> groups = userGroups(authentication);
        if (!enabled || isAdmin(groups)) {
            return manifests;
        }
        return manifests.stream()
                .filter(manifest -> canSee(manifest, groups))
                .map(manifest -> ManifestAccess.visibleTo(manifest, groups))
                .toList();
    }

    public Optional<ManifestDto> visibleManifest(String name, Authentication authentication) {
        Optional<ManifestDto> manifest = manifestBackendService.get(name);
        Set<String> groups = userGroups(authentication);
        if (!enabled || isAdmin(groups)) {
            return manifest;
        }
        return manifest
                .filter(m -> canSee(m, groups))
                .map(m -> ManifestAccess.visibleTo(m, groups));
    }

    /**
     * Whether the user may use the {@code type} service {@code id} (or, with a null id, some
     * service of that type) of the domain/env. No manifest for the domain/env means no access.
     */
    public boolean canAccess(Authentication authentication, String domain, String env, String type, String id) {
        if (!enabled) {
            return true;
        }
        Set<String> groups = userGroups(authentication);
        if (isAdmin(groups)) {
            return true;
        }
        return manifestBackendService.list().stream()
                .filter(m -> Objects.equals(domain, m.getDomain()) && Objects.equals(env, m.getEnv()))
                .filter(m -> !hidden(m))
                .anyMatch(m -> type == null
                        ? ManifestAccess.canSee(m, groups)
                        : ManifestAccess.canAccess(m, groups, type, id));
    }

    /**
     * Why the user may not save this manifest, or empty when they may. Without access control
     * anyone may save anything, as before. With it, a non-admin user:
     * <ul>
     *   <li>may only replace a manifest (same name) whose {@code groups} name one of their groups
     *       - a policy only grants the use of services, not the manifest itself;</li>
     *   <li>may only add a manifest to a domain they already own: every manifest of that domain,
     *       whatever its env, must name one of their groups - no squatting another team's domain,
     *       no new domain (an admin, or tenant provisioning, creates it);</li>
     *   <li>must keep one of their groups in the saved manifest's {@code groups}, which also rules
     *       out unrestricted manifests (no groups, no policies).</li>
     * </ul>
     * The {@code "*"} group, which matches everyone for reading, never counts as ownership here.
     */
    public Optional<String> whySaveDenied(Authentication authentication, ManifestDto manifest) {
        if (!enabled) {
            return Optional.empty();
        }
        Set<String> groups = userGroups(authentication);
        if (isAdmin(groups)) {
            return Optional.empty();
        }
        if (groups.isEmpty()) {
            return Optional.of("Saving a manifest needs an authenticated user with groups");
        }
        if (manifest.getName() != null) {
            Optional<ManifestDto> existing = manifestBackendService.get(manifest.getName());
            if (existing.isPresent() && !owns(existing.get(), groups)) {
                return Optional.of("Manifest %s isn't yours".formatted(manifest.getName()));
            }
        }
        List<ManifestDto> sameDomain = manifestBackendService.list().stream()
                .filter(m -> Objects.equals(manifest.getDomain(), m.getDomain()))
                .toList();
        if (sameDomain.isEmpty()) {
            return Optional.of("Domain %s doesn't exist; only an administrator can create it".formatted(manifest.getDomain()));
        }
        if (!sameDomain.stream().allMatch(m -> owns(m, groups))) {
            return Optional.of("Domain %s isn't yours".formatted(manifest.getDomain()));
        }
        if (!owns(manifest, groups)) {
            return Optional.of("The manifest's groups must include one of your groups");
        }
        return Optional.empty();
    }

    private boolean canSee(ManifestDto manifest, Set<String> groups) {
        return !hidden(manifest) && ManifestAccess.canSee(manifest, groups);
    }

    private boolean hidden(ManifestDto manifest) {
        return hideUnrestricted && !ManifestAccess.isRestricted(manifest);
    }

    private boolean isAdmin(Set<String> groups) {
        return groups.stream().anyMatch(group -> adminGroups.stream().anyMatch(group::equalsIgnoreCase));
    }

    // Explicit membership of the manifest's groups, the "*" wildcard excluded.
    private static boolean owns(ManifestDto manifest, Set<String> groups) {
        Collection<String> owners = manifest.getGroups();
        return owners != null && owners.stream()
                .filter(owner -> owner != null && !WILDCARD.equals(owner))
                .anyMatch(owner -> groups.stream().anyMatch(owner::equalsIgnoreCase));
    }

    private static Set<String> parseGroups(String configured) {
        if (!StringUtils.hasText(configured)) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(group -> !group.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    static Set<String> userGroups(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getAuthorities() == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toSet());
    }
}

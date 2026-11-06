package org.kockpit.backend.tenants;

import lombok.RequiredArgsConstructor;
import org.kockpit.backend.tenants.TenantRepository.ApiKey;
import org.kockpit.backend.tenants.TenantRepository.Tenant;
import org.kockpit.features.manifest.services.backend.ManifestAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenants and API keys of the signed-in local user.
 * <ul>
 *   <li>{@code GET /tenants}: their tenants, envs and keys with today's usage, plus the limits;</li>
 *   <li>{@code POST /tenants} {@code {slug, envs}}: create a tenant;</li>
 *   <li>{@code POST /tenants/{slug}/api-keys} {@code {name}}: create a key - the only response
 *       that holds the key itself;</li>
 *   <li>{@code DELETE /tenants/{slug}/api-keys/{id}}: revoke a key;</li>
 *   <li>administrators: {@code GET /admin/tenants} (every tenant, with its owner) and
 *       {@code PUT /admin/api-keys/{id}/quota} {@code {dailyQuota}}.</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "kockpit.security.local.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TenantApi {

    private final TenantService service;

    private final TenantProperties properties;

    private final ManifestAccessService access;

    public record NewTenant(String slug, List<String> envs) {
    }

    public record NewKey(String name) {
    }

    public record Quota(Integer dailyQuota) {
    }

    @GetMapping("/tenants")
    Map<String, Object> mine(Authentication authentication) {
        UUID userId = userId(authentication);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("admin", access.isAdmin(authentication));
        body.put("today", service.today().toString());
        body.put("limits", Map.of(
                "tenants", properties.getMaxPerUser(),
                "keysPerTenant", properties.getMaxKeysPerTenant(),
                "defaultDailyQuota", properties.getDefaultDailyQuota(),
                "envs", properties.getMaxEnvs()));
        body.put("tenants", service.tenantsOf(userId).stream().map(t -> view(t, false)).toList());
        return body;
    }

    @PostMapping("/tenants")
    ResponseEntity<Map<String, Object>> create(@RequestBody NewTenant request, Authentication authentication) {
        Tenant tenant = service.create(userId(authentication), access.isAdmin(authentication),
                request.slug(), request.envs());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(tenant, false));
    }

    @PostMapping("/tenants/{slug}/api-keys")
    ResponseEntity<Map<String, Object>> createKey(@PathVariable String slug, @RequestBody NewKey request,
                                                  Authentication authentication) {
        Tenant tenant = service.manageable(userId(authentication), access.isAdmin(authentication), slug);
        TenantService.CreatedKey created = service.createKey(tenant, request.name());
        Map<String, Object> body = view(created.apiKey());
        body.put("key", created.key());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/tenants/{slug}/api-keys/{id}")
    ResponseEntity<Void> revokeKey(@PathVariable String slug, @PathVariable String id, Authentication authentication) {
        Tenant tenant = service.manageable(userId(authentication), access.isAdmin(authentication), slug);
        service.revokeKey(tenant, uuid(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/tenants")
    List<Map<String, Object>> all(Authentication authentication) {
        requireAdmin(authentication);
        return service.all().stream().map(t -> view(t, true)).toList();
    }

    @PutMapping("/admin/api-keys/{id}/quota")
    Map<String, Object> setQuota(@PathVariable String id, @RequestBody Quota request, Authentication authentication) {
        requireAdmin(authentication);
        if (request.dailyQuota() == null) {
            throw TenantException.badRequest("dailyQuota is required");
        }
        return view(service.setQuota(uuid(id), request.dailyQuota()));
    }

    @ExceptionHandler(TenantException.class)
    ResponseEntity<Map<String, String>> refused(TenantException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getError(), "message", e.getMessage()));
    }

    private Map<String, Object> view(Tenant tenant, boolean withOwner) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("slug", tenant.slug());
        view.put("domain", tenant.slug());
        view.put("envs", service.envs(tenant));
        view.put("createdAt", tenant.createdAt().toString());
        if (withOwner) {
            view.put("owner", service.ownerEmail(tenant));
        }
        view.put("apiKeys", service.keys(tenant).stream().map(TenantApi::view).toList());
        return view;
    }

    private static Map<String, Object> view(ApiKey key) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", key.id().toString());
        view.put("name", key.name());
        view.put("prefix", key.prefix());
        view.put("dailyQuota", key.dailyQuota());
        view.put("usedToday", key.usedToday());
        view.put("active", key.active());
        view.put("createdAt", text(key.createdAt()));
        view.put("lastUsedAt", text(key.lastUsedAt()));
        view.put("revokedAt", text(key.revokedAt()));
        return view;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private void requireAdmin(Authentication authentication) {
        if (!access.isAdmin(authentication)) {
            throw TenantException.forbidden("Administrators only");
        }
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw TenantException.notFound("No key " + id);
        }
    }

    // local accounts' tokens: subject = user id (LocalAuthConfiguration)
    private static UUID userId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        throw new InsufficientAuthenticationException("A local account is required");
    }
}

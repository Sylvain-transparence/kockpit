package org.kockpit.backend.tenants;

import lombok.extern.slf4j.Slf4j;
import org.kockpit.backend.security.local.UserRepository;
import org.kockpit.backend.tenants.TenantRepository.ApiKey;
import org.kockpit.backend.tenants.TenantRepository.Tenant;
import org.kockpit.features.manifest.services.ManifestJson;
import org.kockpit.features.manifest.services.backend.ManifestBackendService;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.kockpit.features.manifest.services.dto.ServiceDto;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Self-service tenants: a user creates a tenant (one domain), which
 * <ul>
 *   <li>adds them to its group {@code tenant:<slug>};</li>
 *   <li>creates one manifest per env from the template, restricted to that group - so the
 *       domain's audits are visible to its members (and administrators) only, and the members
 *       own the domain's manifests (they may replace them or add some, see
 *       {@code ManifestAccessService.whySaveDenied});</li>
 *   <li>lets them create API keys, each with a daily audit quota, for the ingestion gateway.</li>
 * </ul>
 * Limits: {@link TenantProperties}. Administrators bypass the tenants-per-user limit and may
 * manage every tenant's keys.
 */
@Slf4j
public class TenantService {

    private static final int MAX_KEY_NAME_LENGTH = 100;

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ManifestBackendService manifests;
    private final TransactionTemplate transaction;
    private final TenantProperties properties;
    private final String manifestTemplate;
    private final Clock clock;

    public record CreatedKey(ApiKey apiKey, String key) {
    }

    public TenantService(TenantRepository tenants, UserRepository users, ManifestBackendService manifests,
                         TransactionTemplate transaction, TenantProperties properties,
                         ResourceLoader resourceLoader, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.manifests = manifests;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
        this.manifestTemplate = load(resourceLoader, properties.getManifestTemplate());
        // fail at startup, not at the first tenant creation
        services("check", "check");
    }

    /** Today, UTC: the day quotas count against (the gateway uses the same). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    public Tenant create(UUID userId, boolean admin, String slug, List<String> requestedEnvs) {
        if (!TenantNames.isValidSlug(slug)) {
            throw TenantException.badRequest(
                    "A tenant name is 3 to 30 lower-case letters or digits, starting with a letter");
        }
        if (TenantNames.isReserved(slug)) {
            throw TenantException.conflict("tenant_taken", "Tenant name %s is reserved".formatted(slug));
        }
        List<String> envs = envs(requestedEnvs);
        // an admin-created domain (manifest uploaded without a tenant) is taken too
        boolean domainExists = manifests.list().stream().anyMatch(m -> slug.equalsIgnoreCase(m.getDomain()));
        if (domainExists) {
            throw TenantException.conflict("tenant_taken", "Tenant name %s is taken".formatted(slug));
        }

        Tenant tenant = transaction.execute(status -> {
            tenants.lockUser(userId);
            if (!admin && tenants.countByOwner(userId) >= properties.getMaxPerUser()) {
                throw TenantException.conflict("tenant_limit",
                        "You can create %d tenant(s) at most".formatted(properties.getMaxPerUser()));
            }
            Tenant created = tenants.create(slug, userId, envs).orElseThrow(() ->
                    TenantException.conflict("tenant_taken", "Tenant name %s is taken".formatted(slug)));
            users.addGroup(userId, TenantNames.group(slug));
            return created;
        });

        try {
            for (String env : envs) {
                manifests.save(manifest(slug, env));
            }
        } catch (RuntimeException e) {
            log.error("Could not create the manifests of tenant {}, removing it", slug, e);
            tenants.delete(tenant.id());
            users.removeGroup(userId, TenantNames.group(slug));
            throw new TenantException(HttpStatus.INTERNAL_SERVER_ERROR, "provisioning_failed",
                    "The tenant could not be created, please retry later");
        }
        log.info("Tenant {} created by user {}, envs {}", slug, userId, envs);
        return tenant;
    }

    public List<Tenant> tenantsOf(UUID userId) {
        return tenants.findForUser(userId);
    }

    public List<Tenant> all() {
        return tenants.findAll();
    }

    public List<String> envs(Tenant tenant) {
        return tenants.envs(tenant.id());
    }

    public List<ApiKey> keys(Tenant tenant) {
        return tenants.keys(tenant.id(), today());
    }

    public String ownerEmail(Tenant tenant) {
        return tenants.ownerEmail(tenant.ownerId());
    }

    public CreatedKey createKey(Tenant tenant, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_KEY_NAME_LENGTH || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw TenantException.badRequest("A key name is 1 to %d characters".formatted(MAX_KEY_NAME_LENGTH));
        }
        String key = ApiKeys.generate();
        UUID id = transaction.execute(status -> {
            tenants.lockTenant(tenant.id());
            if (tenants.countActiveKeys(tenant.id()) >= properties.getMaxKeysPerTenant()) {
                throw TenantException.conflict("key_limit",
                        "A tenant can have %d active keys at most: revoke one first".formatted(properties.getMaxKeysPerTenant()));
            }
            return tenants.createKey(tenant.id(), trimmed, ApiKeys.displayPrefix(key), ApiKeys.hash(key),
                    properties.getDefaultDailyQuota());
        });
        log.info("API key {} created for tenant {}", ApiKeys.displayPrefix(key), tenant.slug());
        return new CreatedKey(tenants.findKey(id, today()).orElseThrow(), key);
    }

    public void revokeKey(Tenant tenant, UUID keyId) {
        ApiKey key = tenants.findKey(keyId, today())
                .filter(k -> k.tenantId().equals(tenant.id()))
                .orElseThrow(() -> TenantException.notFound("No key " + keyId));
        if (tenants.revokeKey(key.id())) {
            log.info("API key {} of tenant {} revoked", key.prefix(), tenant.slug());
        }
    }

    public ApiKey setQuota(UUID keyId, int dailyQuota) {
        if (dailyQuota < 0) {
            throw TenantException.badRequest("The daily quota can't be negative");
        }
        if (!tenants.setKeyQuota(keyId, dailyQuota)) {
            throw TenantException.notFound("No key " + keyId);
        }
        log.info("Daily quota of API key {} set to {}", keyId, dailyQuota);
        return tenants.findKey(keyId, today()).orElseThrow();
    }

    private List<String> envs(List<String> requested) {
        List<String> envs = requested == null || requested.isEmpty()
                ? properties.getDefaultEnvs()
                : new ArrayList<>(new LinkedHashSet<>(requested));
        if (envs.size() > properties.getMaxEnvs()) {
            throw TenantException.badRequest("%d envs at most".formatted(properties.getMaxEnvs()));
        }
        for (String env : envs) {
            if (!TenantNames.isValidEnv(env)) {
                throw TenantException.badRequest(
                        "An env name is 2 to 15 lower-case letters or digits, starting with a letter");
            }
        }
        return List.copyOf(envs);
    }

    ManifestDto manifest(String slug, String env) {
        ManifestDto manifest = new ManifestDto();
        manifest.setDomain(slug);
        manifest.setEnv(env);
        manifest.setName(TenantNames.manifestName(slug, env));
        manifest.setGroups(List.of(TenantNames.group(slug)));
        manifest.setServices(services(slug, env));
        return manifest;
    }

    private List<ServiceDto> services(String slug, String env) {
        // slug, env and group are [a-z0-9:] only: nothing to escape in the JSON
        String json = manifestTemplate
                .replace("${domain}", slug)
                .replace("${env}", env)
                .replace("${group}", TenantNames.group(slug));
        JsonNode services = ManifestJson.mapper().readTree(json).get("services");
        if (services == null || !services.isArray()) {
            throw new IllegalStateException("The tenant manifest template has no \"services\" array");
        }
        ManifestDto parsed = ManifestJson.mapper().treeToValue(
                ManifestJson.mapper().createObjectNode().set("services", services), ManifestDto.class);
        return parsed.getServices();
    }

    private static String load(ResourceLoader resourceLoader, String location) {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the tenant manifest template " + location, e);
        }
    }
}

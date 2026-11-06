package org.kockpit.backend.tenants;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tenant slugs (the domain) and env names. Stricter than manifest names: lower-case (they end up
 * in OpenSearch index names) and no '-', '_' or '.', since the index alias is
 * "domain-index-env-read" - with hyphens, "a-auditdata-b"/"c" and "a"/"b-auditdata-c" would
 * share an alias, i.e. one tenant could read another's audits.
 */
public final class TenantNames {

    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9]{2,29}");

    private static final Pattern ENV = Pattern.compile("[a-z][a-z0-9]{1,14}");

    // path segments of the API and names of the platform: a tenant can't take them
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "api", "audit", "audits", "auditdata", "auth", "cache", "config",
            "console", "dashboard", "default", "feature", "heartbeat", "ingest", "kafka", "kockpit",
            "login", "logout", "manifest", "manifests", "me", "opensearch", "postgres", "public",
            "root", "signup", "system", "tenant", "tenants", "www");

    private TenantNames() {
    }

    public static boolean isValidSlug(String slug) {
        return slug != null && SLUG.matcher(slug).matches();
    }

    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug);
    }

    public static boolean isValidEnv(String env) {
        return env != null && ENV.matcher(env).matches();
    }

    /** The group of the tenant's members, which its manifests grant access to. */
    public static String group(String slug) {
        return "tenant:" + slug;
    }

    public static String manifestName(String slug, String env) {
        return "tenant-" + slug + "-" + env + ".json";
    }
}

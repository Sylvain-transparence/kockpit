package org.kockpit.backend.tenants;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** {@code kockpit.tenants.*}: self-service tenants and API keys, see {@link TenantService}. */
@Data
@ConfigurationProperties("kockpit.tenants")
public class TenantProperties {

    /** Tenants one (non-admin) user may create. */
    private int maxPerUser = 1;

    /** Active (non-revoked) API keys per tenant. */
    private int maxKeysPerTenant = 3;

    /** Audits a new API key may send per UTC day; administrators can change it per key. */
    private int defaultDailyQuota = 10_000;

    /** Accounts in a tenant, its owner included. */
    private int maxMembers = 20;

    /** Invitations of a tenant not yet accepted, cancelled or expired. */
    private int maxOpenInvitations = 5;

    /** How long an invitation can be accepted. */
    private Duration invitationTtl = Duration.ofDays(7);

    /** Envs a tenant may declare. */
    private int maxEnvs = 3;

    /** Envs of a tenant created without any. */
    private List<String> defaultEnvs = List.of("dev");

    /**
     * Services of the generated manifests (JSON with a "services" array; ${domain}, ${env} and
     * ${group} are replaced). Domain, env, name and groups are set by the provisioning.
     */
    private String manifestTemplate = "classpath:kockpit/tenant-manifest-template.json";
}

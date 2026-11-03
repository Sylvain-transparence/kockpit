package org.kockpit.features.manifest.services;

import org.junit.jupiter.api.Test;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.kockpit.features.manifest.services.dto.ServiceDto;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestAggregatorTest {

    @Test
    void merges_manifests_sharing_a_domain_and_env_into_one_entry() {
        List<ManifestDto> aggregated = ManifestAggregator.byDomainAndEnv(List.of(
                manifest("platform-auditstream-manifest-dev.json", "platform", "dev", "auditstream"),
                manifest("offers-manifest-dev.json", "offers", "dev", "offers-api"),
                manifest("platform-canary-manifest-dev.json", "platform", "dev", "canary-auditstream")));

        assertThat(aggregated).extracting(ManifestDto::getDomain).containsExactly("platform", "offers");
        ManifestDto platform = aggregated.getFirst();
        assertThat(platform.getEnv()).isEqualTo("dev");
        assertThat(platform.getServices()).extracting(ServiceDto::getId)
                .containsExactly("auditstream", "canary-auditstream");
        assertThat(platform.getName())
                .isEqualTo("platform-auditstream-manifest-dev.json, platform-canary-manifest-dev.json");
        assertThat(platform.getGroups()).isNull();
        assertThat(platform.getPolicies()).isNull();
    }

    @Test
    void keeps_a_service_declared_twice_only_once() {
        List<ManifestDto> aggregated = ManifestAggregator.byDomainAndEnv(List.of(
                manifest("a.json", "messaging", "dev", "same-api"),
                manifest("b.json", "messaging", "dev", "same-api")));

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.getFirst().getServices()).extracting(ServiceDto::getId).containsExactly("same-api");
    }

    @Test
    void same_domain_on_different_envs_stays_separate() {
        List<ManifestDto> aggregated = ManifestAggregator.byDomainAndEnv(List.of(
                manifest("dev.json", "messaging", "dev", "a"),
                manifest("local.json", "messaging", "local", "a")));

        assertThat(aggregated).extracting(ManifestDto::getEnv).containsExactly("dev", "local");
    }

    @Test
    void a_single_manifest_keeps_its_name_and_drops_access_fields() {
        ManifestDto restricted = ManifestReader.read("""
                { "domain": "insurance", "env": "dev", "groups": ["team"],
                  "services": [ { "type": "audit", "name": "a", "id": "a" } ] }
                """.getBytes(StandardCharsets.UTF_8));
        restricted.setName("insurance.json");

        ManifestDto aggregated = ManifestAggregator.byDomainAndEnv(List.of(restricted)).getFirst();

        assertThat(aggregated.getName()).isEqualTo("insurance.json");
        assertThat(aggregated.getServices()).hasSize(1);
        assertThat(aggregated.getGroups()).isNull();
    }

    private static ManifestDto manifest(String name, String domain, String env, String auditServiceId) {
        ManifestDto manifest = ManifestReader.read("""
                { "domain": "%s", "env": "%s",
                  "services": [ { "type": "audit", "name": "%s", "id": "%s" } ] }
                """.formatted(domain, env, auditServiceId, auditServiceId).getBytes(StandardCharsets.UTF_8));
        manifest.setName(name);
        return manifest;
    }
}

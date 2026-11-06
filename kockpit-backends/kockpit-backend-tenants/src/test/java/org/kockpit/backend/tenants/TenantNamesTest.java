package org.kockpit.backend.tenants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantNamesTest {

    @Test
    void slugs_cannot_make_two_tenants_share_an_index_alias() {
        // alias = domain-index-env-read: without '-', no other domain/env split gives the same alias
        assertThat(TenantNames.isValidSlug("acme")).isTrue();
        assertThat(TenantNames.isValidSlug("a-auditdata-b")).isFalse();
        assertThat(TenantNames.isValidEnv("b-auditdata-c")).isFalse();
        assertThat(TenantNames.isValidSlug("acme*")).isFalse();
        assertThat(TenantNames.isValidSlug("Acme")).isFalse();
        assertThat(TenantNames.isValidEnv("prod2")).isTrue();
    }

    @Test
    void keys_are_random_and_hashed() {
        String key = ApiKeys.generate();
        assertThat(key).startsWith("kpk_").hasSize(47).isNotEqualTo(ApiKeys.generate());
        assertThat(ApiKeys.hash(key)).hasSize(64).isEqualTo(ApiKeys.hash(key)).isNotEqualTo(ApiKeys.hash(ApiKeys.generate()));
    }
}

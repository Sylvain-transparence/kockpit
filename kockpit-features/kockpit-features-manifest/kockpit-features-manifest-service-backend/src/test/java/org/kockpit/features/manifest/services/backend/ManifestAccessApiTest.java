package org.kockpit.features.manifest.services.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kockpit.features.manifest.services.ManifestBackendRepository;
import org.kockpit.features.manifest.services.ManifestReader;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /manifests filtering and the 403 guard on domain/env endpoints, wired as in the app
 * (same interceptor, same path patterns), with the user's groups as authorities.
 */
class ManifestAccessApiTest {

    private static final List<ManifestDto> MANIFESTS = List.of(
            manifest("""
                    { "domain": "insurance", "env": "dev", "name": "insurance-dev",
                      "groups": ["KOCKPIT-Console-InsuranceTeam"], "policies": null,
                      "services": [ { "type": "audit", "name": "a", "id": "a" } ] }"""),
            manifest("""
                    { "domain": "offers", "env": "review-mr-1551", "name": "offers-review",
                      "groups": null,
                      "policies": [
                        {"groups":["KOCKPIT-Console-QaTeam"],"permissions":[],"resources":["audit:*"]},
                        {"groups":["KOCKPIT-Console-OffersTeam"],"permissions":[],"resources":["audit:*","cache:*","dynaconfig:*","featureflipping:*","sqsdlq:*"]}
                      ],
                      "services": [ { "type": "audit", "name": "offers-api", "id": "offers-api" } ] }"""),
            manifest("""
                    { "domain": "messaging", "env": "dev", "name": "messaging-dev",
                      "services": [ { "type": "audit", "name": "c", "id": "c" } ] }"""),
            // Second manifest for offers/review-mr-1551, restricted to its own team.
            manifest("""
                    { "domain": "offers", "env": "review-mr-1551", "name": "offers-review-business",
                      "groups": ["KOCKPIT-Console-OffersBusinessTeam"],
                      "services": [ { "type": "audit", "name": "offers-business-api", "id": "offers-business-api" } ] }"""));

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void manifests_only_lists_what_the_user_can_see() throws Exception {
        MockMvc mvc = mvc(true);

        loginWith("KOCKPIT-Console-OffersTeam");
        mvc.perform(asUser(get("/manifests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].domain").value("offers"))
                .andExpect(jsonPath("$[1].domain").value("messaging"));

        loginWith("KOCKPIT-Console-ReadOnly");
        mvc.perform(asUser(get("/manifests")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].domain").value("messaging"));
    }

    @Test
    void aggregated_merges_per_domain_env_after_per_manifest_access_filtering() throws Exception {
        MockMvc mvc = mvc(true);

        // Member of both offers manifests' audiences: one offers entry with both services.
        loginWith("KOCKPIT-Console-OffersTeam", "KOCKPIT-Console-OffersBusinessTeam");
        mvc.perform(asUser(get("/manifests/aggregated")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].domain").value("offers"))
                .andExpect(jsonPath("$[0].services.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("offers-review, offers-review-business"))
                .andExpect(jsonPath("$[1].domain").value("messaging"));

        // Only the first manifest's policy matches: its service only, not the restricted one's.
        loginWith("KOCKPIT-Console-OffersTeam");
        mvc.perform(asUser(get("/manifests/aggregated")))
                .andExpect(jsonPath("$[0].services.length()").value(1))
                .andExpect(jsonPath("$[0].services[0].id").value("offers-api"));
    }

    @Test
    void domain_env_endpoints_are_forbidden_without_access() throws Exception {
        MockMvc mvc = mvc(true);

        loginWith("KOCKPIT-Console-ReadOnly");
        mvc.perform(get("/insurance/dev/audits/_search")).andExpect(status().isForbidden());
        mvc.perform(get("/insurance/dev/heartbeat")).andExpect(status().isForbidden());
        mvc.perform(get("/messaging/dev/audits/_search")).andExpect(status().isOk());
        mvc.perform(get("/unknown/dev/audits/_search")).andExpect(status().isForbidden());

        loginWith("KOCKPIT-Console-InsuranceTeam");
        mvc.perform(get("/insurance/dev/audits/_search")).andExpect(status().isOk());
        mvc.perform(get("/insurance/dev/dashboard/app_details")).andExpect(status().isOk());
        mvc.perform(get("/insurance/dev/cache/anything")).andExpect(status().isOk());
    }

    @Test
    void policies_scope_access_by_service_type() throws Exception {
        MockMvc mvc = mvc(true);

        loginWith("KOCKPIT-Console-QaTeam");
        mvc.perform(get("/offers/review-mr-1551/audits/_search")).andExpect(status().isOk());
        mvc.perform(get("/offers/review-mr-1551/cache/currenciesCache")).andExpect(status().isForbidden());
        mvc.perform(get("/offers/review-mr-1551/heartbeat")).andExpect(status().isOk());

        loginWith("KOCKPIT-Console-OffersTeam");
        mvc.perform(get("/offers/review-mr-1551/cache/currenciesCache")).andExpect(status().isOk());
        mvc.perform(get("/offers/review-mr-1551/dyna-config/offers-api")).andExpect(status().isOk());
    }

    @Test
    void without_cognito_configured_nothing_is_filtered() throws Exception {
        MockMvc mvc = mvc(false);

        mvc.perform(get("/manifests")).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/insurance/dev/audits/_search")).andExpect(status().isOk());
    }

    private static MockMvc mvc(boolean cognitoConfigured) {
        ManifestBackendService backend = new ManifestBackendService(new InMemoryRepository());
        ManifestAccessService access = new ManifestAccessService(backend,
                cognitoConfigured ? "https://cognito-idp.eu-west-1.amazonaws.com/pool" : "");
        return MockMvcBuilders.standaloneSetup(new ManifestApi(backend, access), new FeatureEndpoints())
                .addMappedInterceptors(ManifestAccessWebConfig.pathPatterns(), new ManifestAccessInterceptor(access))
                .build();
    }

    // The real app's security filters expose the user both ways: to the interceptor through the
    // SecurityContext and to controller Authentication parameters through the request principal.
    private static void loginWith(String... groups) {
        TestingAuthenticationToken user = new TestingAuthenticationToken("user", null, groups);
        user.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(user);
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request.principal(SecurityContextHolder.getContext().getAuthentication());
    }

    private static ManifestDto manifest(String json) {
        return ManifestReader.read(json.getBytes(StandardCharsets.UTF_8));
    }

    // Same paths as the real feature controllers (SearchApi, DashboardApi, CacheApi, ...).
    @RestController
    static class FeatureEndpoints {
        @GetMapping("/{domain}/{env}/audits/_search")
        String audits() { return "ok"; }

        @GetMapping("/{domain}/{env}/dashboard/app_details")
        String dashboard() { return "ok"; }

        @GetMapping("/{domain}/{env}/cache/{id}")
        String cache() { return "ok"; }

        @GetMapping("/{domain}/{env}/dyna-config/{id}")
        String dynaConfig() { return "ok"; }

        @GetMapping("/{domain}/{env}/heartbeat")
        String heartbeat() { return "ok"; }
    }

    static class InMemoryRepository implements ManifestBackendRepository {
        @Override
        public List<ManifestDto> findAll() { return MANIFESTS; }

        @Override
        public Optional<ManifestDto> findByName(String name) {
            return MANIFESTS.stream().filter(m -> name.equals(m.getName())).findFirst();
        }

        @Override
        public ManifestDto save(ManifestDto manifestDto) { return manifestDto; }
    }
}

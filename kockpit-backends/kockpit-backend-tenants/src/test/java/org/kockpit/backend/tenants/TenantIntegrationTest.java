package org.kockpit.backend.tenants;

import org.junit.jupiter.api.Test;
import org.kockpit.backend.security.local.UserRepository;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Tenants and API keys end to end: real HTTP with local accounts, PostgreSQL in a container. */
@SpringBootTest(classes = TenantTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "kockpit.security.local.enabled=true",
                "kockpit.security.local.jwt-secret=test-secret-test-secret-test-secret-0123",
                "kockpit.security.local.signup.per-ip-per-hour=1000",
                "kockpit.security.local.signup.per-hour=1000",
                "kockpit.security.local.admin.email=admin@example.com",
                "kockpit.security.local.admin.password=admin-password-123",
                "kockpit.security.access-control.admin-groups=kockpit-admins",
                "kockpit.security.access-control.hide-unrestricted=true",
                "kockpit.tenants.max-keys-per-tenant=2",
                "kockpit.tenants.default-daily-quota=500",
                "kockpit.tenants.max-open-invitations=2",
                "kockpit.tenants.max-members=3",
        })
@Testcontainers
class TenantIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("kockpit.security.local.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("kockpit.security.local.datasource.username", POSTGRES::getUsername);
        registry.add("kockpit.security.local.datasource.password", POSTGRES::getPassword);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    TenantTestApplication.InMemoryManifests manifests;

    @Autowired
    DataSource dataSource;

    @Autowired
    TenantTestApplication.SavedManifests saved;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void a_new_tenant_gets_restricted_manifests_and_its_group() throws Exception {
        String cookie = signup();
        String slug = uniqueSlug();

        HttpResponse<String> created = send("POST", "/tenants", "{\"slug\":\"%s\",\"envs\":[\"dev\",\"prod\"]}".formatted(slug), cookie);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json(created).get("envs").toString()).isEqualTo("[\"dev\",\"prod\"]");
        ManifestDto prod = manifests.byName.get("tenant-" + slug + "-prod.json");
        assertThat(prod.getDomain()).isEqualTo(slug);
        assertThat(prod.getEnv()).isEqualTo("prod");
        assertThat(prod.getGroups()).containsExactly("tenant:" + slug);
        assertThat(prod.getServices()).extracting("type").containsExactly("audit");
        assertThat(prod.getServices().getFirst().getId()).isEqualTo("audit-prod");
        assertThat(saved.names)
                .contains("tenant-" + slug + "-dev.json", "tenant-" + slug + "-prod.json");
        // the group is read from the database on each request: no new login needed
        assertThat(send("GET", "/me", null, cookie).body()).contains("tenant:" + slug);
        assertThat(json(send("GET", "/manifests", null, cookie))).extracting(m -> m.get("domain").asString())
                .containsOnly(slug);
        assertThat(json(send("GET", "/tenants", null, cookie)).get("tenants").get(0).get("slug").asString()).isEqualTo(slug);
        assertThat(send("GET", "/" + slug + "/dev/audits/_search", null, cookie).statusCode()).isEqualTo(200);
        // someone else neither sees the manifests nor the audits
        String other = signup();
        assertThat(json(send("GET", "/manifests", null, other))).isEmpty();
        assertThat(send("GET", "/" + slug + "/dev/audits/_search", null, other).statusCode()).isEqualTo(403);
    }

    @Test
    void a_user_creates_one_tenant() throws Exception {
        String cookie = signup();
        assertThat(createTenant(cookie, uniqueSlug()).statusCode()).isEqualTo(201);

        HttpResponse<String> second = createTenant(cookie, uniqueSlug());

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("tenant_limit");
    }

    @Test
    void tenant_names_are_strict_and_unique() throws Exception {
        String cookie = signup();
        for (String invalid : List.of("Acme", "ac", "my-team", "my_team", "a.b", "1acme", "acme*", "acme,other")) {
            assertThat(createTenant(cookie, invalid).statusCode()).as(invalid).isEqualTo(400);
        }
        assertThat(send("POST", "/tenants", "{\"slug\":\"%s\",\"envs\":[\"Prod\"]}".formatted(uniqueSlug()), cookie).statusCode())
                .isEqualTo(400);
        assertThat(send("POST", "/tenants", "{\"slug\":\"%s\",\"envs\":[\"a1\",\"b1\",\"c1\",\"d1\"]}".formatted(uniqueSlug()), cookie).statusCode())
                .isEqualTo(400);
        assertThat(createTenant(cookie, "kockpit").body()).contains("tenant_taken");
        // a domain that exists without a tenant (manifest uploaded by an administrator)
        assertThat(createTenant(cookie, "insurance").body()).contains("tenant_taken");

        String slug = uniqueSlug();
        assertThat(createTenant(signup(), slug).statusCode()).isEqualTo(201);
        assertThat(createTenant(cookie, slug).statusCode()).isEqualTo(409);
    }

    @Test
    void api_keys_are_shown_once_stored_hashed_and_limited() throws Exception {
        String cookie = signup();
        String slug = uniqueSlug();
        createTenant(cookie, slug);

        HttpResponse<String> created = createKey(cookie, slug, "laptop");

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode key = json(created);
        String secret = key.get("key").asString();
        assertThat(secret).startsWith("kpk_").hasSize(47);
        assertThat(key.get("prefix").asString()).isEqualTo(secret.substring(0, 12));
        assertThat(key.get("dailyQuota").asInt()).isEqualTo(500);
        assertThat(key.get("usedToday").asLong()).isZero();
        String stored = JdbcClient.create(dataSource).sql("select key_hash from api_keys where id = :id")
                .param("id", UUID.fromString(key.get("id").asString())).query(String.class).single();
        assertThat(stored).isEqualTo(ApiKeys.hash(secret)).doesNotContain(secret);
        String listed = send("GET", "/tenants", null, cookie).body();
        assertThat(listed).contains(key.get("prefix").asString()).doesNotContain(secret);

        assertThat(createKey(cookie, slug, "ci").statusCode()).isEqualTo(201);
        assertThat(createKey(cookie, slug, "third").body()).contains("key_limit");
        assertThat(createKey(cookie, slug, "  ").statusCode()).isEqualTo(400);

        assertThat(send("DELETE", "/tenants/" + slug + "/api-keys/" + key.get("id").asString(), null, cookie).statusCode())
                .isEqualTo(204);
        assertThat(createKey(cookie, slug, "third").statusCode()).isEqualTo(201);
        JsonNode revoked = json(send("GET", "/tenants", null, cookie)).get("tenants").get(0).get("apiKeys").get(0);
        assertThat(revoked.get("active").asBoolean()).isFalse();
        assertThat(revoked.get("revokedAt").isNull()).isFalse();
    }

    @Test
    void another_users_tenant_looks_absent() throws Exception {
        String owner = signup();
        String slug = uniqueSlug();
        createTenant(owner, slug);
        String keyId = json(createKey(owner, slug, "laptop")).get("id").asString();
        String intruder = signup();

        assertThat(createKey(intruder, slug, "mine").statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/tenants/" + slug + "/api-keys/" + keyId, null, intruder).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/admin/tenants", null, intruder).statusCode()).isEqualTo(403);
        assertThat(send("PUT", "/admin/api-keys/" + keyId + "/quota", "{\"dailyQuota\":1000000}", intruder).statusCode())
                .isEqualTo(403);
        // nor through another tenant of theirs
        String own = uniqueSlug();
        createTenant(intruder, own);
        assertThat(send("DELETE", "/tenants/" + own + "/api-keys/" + keyId, null, intruder).statusCode()).isEqualTo(404);
    }

    @Test
    void administrators_see_every_tenant_and_set_quotas() throws Exception {
        String owner = signup();
        String slug = uniqueSlug();
        createTenant(owner, slug);
        String keyId = json(createKey(owner, slug, "laptop")).get("id").asString();
        String admin = cookieOf(send("POST", "/auth/login", "{\"email\":\"admin@example.com\",\"password\":\"admin-password-123\"}", null));

        JsonNode all = json(send("GET", "/admin/tenants", null, admin));
        assertThat(all).anySatisfy(t -> {
            assertThat(t.get("slug").asString()).isEqualTo(slug);
            assertThat(t.get("owner").asString()).startsWith("user-");
        });
        HttpResponse<String> quota = send("PUT", "/admin/api-keys/" + keyId + "/quota", "{\"dailyQuota\":25}", admin);
        assertThat(quota.statusCode()).isEqualTo(200);
        assertThat(json(quota).get("dailyQuota").asInt()).isEqualTo(25);
        assertThat(send("PUT", "/admin/api-keys/" + keyId + "/quota", "{\"dailyQuota\":-1}", admin).statusCode()).isEqualTo(400);
        // no tenants-per-user limit for administrators, and they manage any tenant's keys
        assertThat(createTenant(admin, uniqueSlug()).statusCode()).isEqualTo(201);
        assertThat(createTenant(admin, uniqueSlug()).statusCode()).isEqualTo(201);
        assertThat(send("DELETE", "/tenants/" + slug + "/api-keys/" + keyId, null, admin).statusCode()).isEqualTo(204);
    }

    @Test
    void a_failed_provisioning_leaves_nothing_behind() throws Exception {
        String cookie = signup();
        String slug = "broken" + ThreadLocalRandom.current().nextInt(1_000_000);

        HttpResponse<String> created = createTenant(cookie, slug);

        assertThat(created.statusCode()).isEqualTo(500);
        assertThat(created.body()).contains("provisioning_failed").doesNotContain("storage unavailable");
        assertThat(send("GET", "/me", null, cookie).body()).doesNotContain("tenant:" + slug);
        assertThat(json(send("GET", "/tenants", null, cookie)).get("tenants")).isEmpty();
        // and the user may try again with another name
        assertThat(createTenant(cookie, uniqueSlug()).statusCode()).isEqualTo(201);
    }

    @Test
    void the_tenant_api_needs_a_login() throws Exception {
        assertThat(send("GET", "/tenants", null, null).statusCode()).isEqualTo(401);
        assertThat(createTenant(null, uniqueSlug()).statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------------------------------------
    // members and invitations
    // ---------------------------------------------------------------------------------------------

    @Test
    void an_invited_account_joins_and_sees_the_tenants_audits() throws Exception {
        String owner = signup();
        String slug = uniqueSlug();
        createTenant(owner, slug);
        String member = signup();
        assertThat(send("GET", "/" + slug + "/dev/audits/_search", null, member).statusCode()).isEqualTo(403);

        HttpResponse<String> invited = send("POST", "/tenants/" + slug + "/invitations", "{}", owner);

        assertThat(invited.statusCode()).as(invited.body()).isEqualTo(201);
        String code = json(invited).get("code").asString();
        assertThat(code).startsWith("kpi_").hasSize(47);
        // stored hashed only, listed without its code
        assertThat(JdbcClient.create(dataSource).sql("select count(*) from tenant_invitations where code_hash = :h")
                .param("h", ApiKeys.hash(code)).query(Integer.class).single()).isEqualTo(1);
        JsonNode open = json(send("GET", "/tenants/" + slug + "/invitations", null, owner));
        assertThat(open).hasSize(1);
        assertThat(open.get(0).has("code")).isFalse();

        JsonNode preview = json(send("POST", "/invitations/preview", codeBody(code), member));
        assertThat(preview.get("tenant").asString()).isEqualTo(slug);
        assertThat(preview.get("alreadyMember").asBoolean()).isFalse();

        HttpResponse<String> accepted = send("POST", "/invitations/accept", codeBody(code), member);

        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(json(accepted).get("role").asString()).isEqualTo("member");
        // at once, without a new login: groups are read on each request
        assertThat(send("GET", "/" + slug + "/dev/audits/_search", null, member).statusCode()).isEqualTo(200);
        assertThat(json(send("GET", "/manifests", null, member))).extracting(m -> m.get("domain").asString()).contains(slug);
        JsonNode mine = json(send("GET", "/tenants", null, member)).get("tenants");
        assertThat(mine).extracting(t -> t.get("slug").asString() + ":" + t.get("role").asString()).containsExactly(slug + ":member");
        assertThat(json(send("GET", "/tenants", null, owner)).get("tenants").get(0).get("role").asString()).isEqualTo("owner");
        JsonNode members = json(send("GET", "/tenants/" + slug + "/members", null, member));
        assertThat(members).extracting(m -> m.get("owner").asBoolean()).containsExactly(true, false);
        assertThat(members).extracting(m -> m.get("you").asBoolean()).containsExactly(false, true);
        assertThat(send("GET", "/tenants/" + slug + "/invitations", null, owner).body()).isEqualTo("[]");

        // single use
        HttpResponse<String> reused = send("POST", "/invitations/accept", codeBody(code), signup());
        assertThat(reused.statusCode()).isEqualTo(404);
        assertThat(reused.body()).contains("invalid_invitation");
    }

    @Test
    void members_see_but_only_the_owner_manages() throws Exception {
        String owner = signup();
        String slug = uniqueSlug();
        createTenant(owner, slug);
        String member = join(owner, slug, signup());

        assertThat(createKey(member, slug, "mine").statusCode()).isEqualTo(403);
        assertThat(send("POST", "/tenants/" + slug + "/invitations", "{}", member).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/tenants/" + slug + "/invitations", null, member).statusCode()).isEqualTo(403);
        String keyId = json(createKey(owner, slug, "ci")).get("id").asString();
        assertThat(send("DELETE", "/tenants/" + slug + "/api-keys/" + keyId, null, member).statusCode()).isEqualTo(403);
        assertThat(json(send("GET", "/tenants", null, member)).get("tenants").get(0).get("apiKeys")).hasSize(1);
        String ownerId = members(owner, slug).get(0).get("userId").asString();
        assertThat(send("DELETE", "/tenants/" + slug + "/members/" + ownerId, null, member).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/tenants/" + slug + "/members/" + ownerId, null, owner).statusCode()).isEqualTo(409);

        // the owner removes the member: no access any more, at once
        String memberId = members(owner, slug).get(1).get("userId").asString();
        assertThat(send("DELETE", "/tenants/" + slug + "/members/" + memberId, null, owner).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/" + slug + "/dev/audits/_search", null, member).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/tenants/" + slug + "/members", null, member).statusCode()).isEqualTo(404);

        // a member may leave
        String leaver = join(owner, slug, signup());
        String leaverId = members(owner, slug).get(1).get("userId").asString();
        assertThat(send("DELETE", "/tenants/" + slug + "/members/" + leaverId, null, leaver).statusCode()).isEqualTo(204);
        assertThat(json(send("GET", "/tenants", null, leaver)).get("tenants")).isEmpty();
        // someone else's tenant looks absent
        assertThat(send("GET", "/tenants/" + slug + "/members", null, signup()).statusCode()).isEqualTo(404);
    }

    @Test
    void an_invitation_for_an_email_is_for_that_account_only() throws Exception {
        String owner = signup();
        String slug = uniqueSlug();
        createTenant(owner, slug);
        String email = "invited-" + UUID.randomUUID() + "@example.com";
        String code = json(send("POST", "/tenants/" + slug + "/invitations",
                "{\"email\":\"%s\"}".formatted(email.toUpperCase()), owner)).get("code").asString();

        HttpResponse<String> wrong = send("POST", "/invitations/accept", codeBody(code), signup());
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(wrong.body()).doesNotContain(email);

        assertThat(send("POST", "/invitations/accept", codeBody(code), signupAs(email)).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/tenants/" + slug + "/invitations", "{\"email\":\"not-an-email\"}", owner).statusCode()).isEqualTo(400);
    }

    @Test
    void cancelled_expired_and_unknown_invitations_are_refused_alike() throws Exception {
        String owner = signup();
        String slug = uniqueSlug();
        createTenant(owner, slug);
        String guest = signup();
        JsonNode cancelled = json(send("POST", "/tenants/" + slug + "/invitations", "{}", owner));
        assertThat(send("DELETE", "/tenants/" + slug + "/invitations/" + cancelled.get("id").asString(), null, owner).statusCode())
                .isEqualTo(204);
        JsonNode expired = json(send("POST", "/tenants/" + slug + "/invitations", "{}", owner));
        JdbcClient.create(dataSource).sql("update tenant_invitations set expires_at = now() - interval '1 minute' where id = :id")
                .param("id", UUID.fromString(expired.get("id").asString())).update();

        for (String code : List.of(cancelled.get("code").asString(), expired.get("code").asString(),
                "kpi_" + "x".repeat(43), "not-a-code")) {
            HttpResponse<String> refused = send("POST", "/invitations/accept", codeBody(code), guest);
            assertThat(refused.statusCode()).as(code).isEqualTo(404);
            assertThat(refused.body()).contains("invalid_invitation");
        }
        assertThat(send("GET", "/" + slug + "/dev/audits/_search", null, guest).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/invitations/accept", codeBody("x"), null).statusCode()).isEqualTo(401);
    }

    @Test
    void invitations_and_members_are_limited() throws Exception {
        String owner = signup();
        String slug = uniqueSlug();
        createTenant(owner, slug);
        String first = json(send("POST", "/tenants/" + slug + "/invitations", "{}", owner)).get("code").asString();
        String second = json(send("POST", "/tenants/" + slug + "/invitations", "{}", owner)).get("code").asString();
        HttpResponse<String> third = send("POST", "/tenants/" + slug + "/invitations", "{}", owner);
        assertThat(third.statusCode()).isEqualTo(409);
        assertThat(third.body()).contains("invitation_limit");

        // max-members=3: the owner and two members
        String withOwnTenant = signup();
        createTenant(withOwnTenant, uniqueSlug());
        // joining doesn't count against the tenants one may create
        assertThat(send("POST", "/invitations/accept", codeBody(first), withOwnTenant).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/invitations/accept", codeBody(second), signup()).statusCode()).isEqualTo(200);
        HttpResponse<String> full = send("POST", "/tenants/" + slug + "/invitations", "{}", owner);
        assertThat(full.statusCode()).isEqualTo(409);
        assertThat(full.body()).contains("member_limit");
    }

    private String join(String ownerCookie, String slug, String memberCookie) throws Exception {
        String code = json(send("POST", "/tenants/" + slug + "/invitations", "{}", ownerCookie)).get("code").asString();
        assertThat(send("POST", "/invitations/accept", codeBody(code), memberCookie).statusCode()).isEqualTo(200);
        return memberCookie;
    }

    private JsonNode members(String cookie, String slug) throws Exception {
        return json(send("GET", "/tenants/" + slug + "/members", null, cookie));
    }

    private static String codeBody(String code) {
        return "{\"code\":\"%s\"}".formatted(code);
    }

    private String signupAs(String email) throws Exception {
        HttpResponse<String> signup = send("POST", "/auth/signup",
                "{\"email\":\"%s\",\"password\":\"a-good-password\"}".formatted(email), null);
        assertThat(signup.statusCode()).isEqualTo(201);
        return cookieOf(signup);
    }

    private String signup() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> signup = send("POST", "/auth/signup",
                "{\"email\":\"%s\",\"password\":\"a-good-password\"}".formatted(email), null);
        assertThat(signup.statusCode()).isEqualTo(201);
        return cookieOf(signup);
    }

    private HttpResponse<String> createTenant(String cookie, String slug) throws Exception {
        return send("POST", "/tenants", "{\"slug\":\"%s\"}".formatted(slug), cookie);
    }

    private HttpResponse<String> createKey(String cookie, String slug, String name) throws Exception {
        return send("POST", "/tenants/" + slug + "/api-keys", "{\"name\":\"%s\"}".formatted(name), cookie);
    }

    private static String uniqueSlug() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String cookieOf(HttpResponse<?> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> send(String method, String path, String json, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

package org.kockpit.gateway;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway end to end: real HTTP, PostgreSQL with the backend's own migrations (keys created
 * as the backend stores them), messages on the filesystem, audits in a recording sink (no Kafka).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kockpit.gateway.audits.max-batch=50",
        "kockpit.gateway.audits.max-body-bytes=20000",
        "kockpit.gateway.audits.ttl-days=7",
        "management.server.port=0",
})
@Testcontainers
class GatewayIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static final Path MESSAGES;

    static {
        try {
            MESSAGES = Files.createTempDirectory("gateway-messages");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("kockpit.communication.filesystem.path", MESSAGES::toString);
    }

    /** Records what would go to Kafka; {@link #failing} simulates Kafka being down. */
    static class RecordingSink implements AuditSink {
        final List<Record> records = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void send(List<Record> batch) throws Exception {
            if (failing) {
                throw new IllegalStateException("kafka down");
            }
            records.addAll(batch);
        }
    }

    @TestConfiguration
    static class Sink {
        @Bean
        @Primary
        RecordingSink recordingSink() {
            return new RecordingSink();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    RecordingSink sink;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private static volatile boolean migrated;

    @BeforeEach
    void schema() {
        if (!migrated) {
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/kockpit-accounts")
                    .table("kockpit_accounts_schema_history")
                    .load().migrate();
            migrated = true;
        }
        sink.records.clear();
        sink.failing = false;
    }

    @Test
    void audits_are_forced_into_the_keys_domain_and_counted() throws Exception {
        Key key = key("acme" + suffix(), List.of("dev", "prod"), 100);

        HttpResponse<String> response = postAudits(key.secret(), """
                [{"id":"a1","domain":"someone-else","env":"dev","appId":"shop","ttl":3650,"requestId":"r1"},
                 {"domain":"*","env":"prod","appId":"shop","audits":[{"name":"x"}]}]""");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json(response).get("accepted").asInt()).isEqualTo(2);
        assertThat(json(response).get("remaining").asLong()).isEqualTo(98);
        assertThat(response.headers().firstValue("X-Quota-Remaining")).hasValue("98");
        assertThat(sink.records).hasSize(2);
        JsonNode first = JSON.readTree(sink.records.get(0).value());
        assertThat(first.get("domain").asString()).isEqualTo(key.domain());
        assertThat(first.get("ttl").asInt()).isEqualTo(7);
        assertThat(first.get("requestId").asString()).isEqualTo("r1");
        assertThat(sink.records.get(0).key()).isEqualTo(key.domain() + "-dev-shop");
        JsonNode second = JSON.readTree(sink.records.get(1).value());
        assertThat(second.get("domain").asString()).isEqualTo(key.domain());
        assertThat(second.get("id").asString()).isNotBlank();
        assertThat(second.get("audits").get(0).get("name").asString()).isEqualTo("x");
        assertThat(usedToday(key)).isEqualTo(2);
        assertThat(jdbc.sql("select last_used_at is not null from api_keys where id = :id").param("id", key.id())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void a_single_audit_and_gzip_are_accepted() throws Exception {
        Key key = key("gz" + suffix(), List.of("dev"), 100);
        assertThat(postAudits(key.secret(), "{\"env\":\"dev\",\"appId\":\"shop\"}").statusCode()).isEqualTo(202);

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write("[{\"env\":\"dev\",\"appId\":\"shop\"}]".getBytes(StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/ingest/v1/audits"))
                .header("X-Kockpit-Api-Key", key.secret()).header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzipped.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(usedToday(key)).isEqualTo(2);
    }

    @Test
    void keys_are_required_and_must_be_active() throws Exception {
        assertThat(postAudits(null, "[]").statusCode()).isEqualTo(401);
        assertThat(postAudits("kpk_" + "x".repeat(43), "[]").body()).contains("invalid_api_key");
        Key revoked = key("rv" + suffix(), List.of("dev"), 100);
        jdbc.sql("update api_keys set revoked_at = now() where id = :id").param("id", revoked.id()).update();
        assertThat(postAudits(revoked.secret(), "{\"env\":\"dev\",\"appId\":\"a\"}").statusCode()).isEqualTo(401);
        // also as a bearer token
        Key bearer = key("br" + suffix(), List.of("dev"), 100);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/ingest/v1/audits"))
                .header("Authorization", "Bearer " + bearer.secret()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"env\":\"dev\",\"appId\":\"a\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
    }

    @Test
    void an_invalid_audit_refuses_the_whole_batch() throws Exception {
        Key key = key("inv" + suffix(), List.of("dev"), 100);
        assertThat(postAudits(key.secret(), "[{\"env\":\"dev\",\"appId\":\"a\"},{\"env\":\"prod\",\"appId\":\"a\"}]").body())
                .contains("Audit #1", "env prod");
        assertThat(postAudits(key.secret(), "{\"appId\":\"a\"}").statusCode()).isEqualTo(400);
        assertThat(postAudits(key.secret(), "{\"env\":\"dev\"}").body()).contains("appId");
        assertThat(postAudits(key.secret(), "{\"env\":\"dev\",\"appId\":\"../../etc\"}").statusCode()).isEqualTo(400);
        assertThat(postAudits(key.secret(), "{\"env\":\"dev\",\"appId\":\"a\",\"id\":\"a/b\"}").statusCode()).isEqualTo(400);
        assertThat(postAudits(key.secret(), "not json").statusCode()).isEqualTo(400);
        assertThat(postAudits(key.secret(), "42").statusCode()).isEqualTo(400);
        assertThat(postAudits(key.secret(), "[]").statusCode()).isEqualTo(400);
        String tooMany = "[" + String.join(",", java.util.Collections.nCopies(51, "{\"env\":\"dev\",\"appId\":\"a\"}")) + "]";
        assertThat(postAudits(key.secret(), tooMany).body()).contains("50 audits per request");
        String tooLarge = "[{\"env\":\"dev\",\"appId\":\"a\",\"x\":\"" + "y".repeat(25_000) + "\"}]";
        assertThat(postAudits(key.secret(), tooLarge).statusCode()).isEqualTo(413);
        assertThat(sink.records).isEmpty();
        assertThat(usedToday(key)).isZero();
    }

    @Test
    void the_daily_quota_refuses_whole_batches() throws Exception {
        Key key = key("qt" + suffix(), List.of("dev"), 3);
        String two = "[{\"env\":\"dev\",\"appId\":\"a\"},{\"env\":\"dev\",\"appId\":\"a\"}]";
        String one = "{\"env\":\"dev\",\"appId\":\"a\"}";

        assertThat(postAudits(key.secret(), two).statusCode()).isEqualTo(202);
        HttpResponse<String> over = postAudits(key.secret(), two);
        assertThat(over.statusCode()).isEqualTo(429);
        assertThat(over.body()).contains("quota_exceeded", "1 left");
        assertThat(over.headers().firstValue("X-Quota-Remaining")).hasValue("1");
        assertThat(Long.parseLong(over.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 86_400L);
        assertThat(postAudits(key.secret(), one).statusCode()).isEqualTo(202);
        HttpResponse<String> full = postAudits(key.secret(), one);
        assertThat(full.statusCode()).isEqualTo(429);
        assertThat(full.headers().firstValue("X-Quota-Remaining")).hasValue("0");
        assertThat(usedToday(key)).isEqualTo(3);
        assertThat(sink.records).hasSize(3);
    }

    @Test
    void concurrent_requests_never_overshoot_the_quota() throws Exception {
        Key key = key("cc" + suffix(), List.of("dev"), 10);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                statuses.add(pool.submit(() -> postAudits(key.secret(), "{\"env\":\"dev\",\"appId\":\"a\"}").statusCode()));
            }
            int accepted = 0;
            for (Future<Integer> status : statuses) {
                accepted += status.get() == 202 ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(10);
        } finally {
            pool.shutdown();
        }
        assertThat(usedToday(key)).isEqualTo(10);
    }

    @Test
    void undelivered_audits_are_not_counted() throws Exception {
        Key key = key("dn" + suffix(), List.of("dev"), 100);
        sink.failing = true;

        HttpResponse<String> response = postAudits(key.secret(), "{\"env\":\"dev\",\"appId\":\"a\"}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("kafka down");
        assertThat(usedToday(key)).isZero();
    }

    @Test
    void messages_stay_in_the_keys_domain() throws Exception {
        Key key = key("msg" + suffix(), List.of("dev"), 100);
        Key other = key("oth" + suffix(), List.of("dev"), 100);

        HttpResponse<String> published = send("POST", "/ingest/v1/messages", key.secret(),
                "{\"domain\":\"" + other.domain() + "\",\"env\":\"dev\",\"appId\":\"shop\",\"type\":\"heartbeat\",\"body\":{\"up\":true}}");

        assertThat(published.statusCode()).as(published.body()).isEqualTo(202);
        String id = json(published).get("id").asString();
        assertThat(MESSAGES.resolve(key.domain()).resolve("dev").resolve("shop-" + id + ".json")).exists();
        assertThat(MESSAGES.resolve(other.domain())).doesNotExist();
        JsonNode polled = json(send("GET", "/ingest/v1/messages?env=dev&type=heartbeat", key.secret(), null));
        assertThat(polled).hasSize(1);
        assertThat(polled.get(0).get("domain").asString()).isEqualTo(key.domain());
        assertThat(json(send("GET", "/ingest/v1/messages?env=dev&type=feature-flipping", key.secret(), null))).isEmpty();
        assertThat(json(send("GET", "/ingest/v1/messages?env=dev", other.secret(), null))).isEmpty();
        assertThat(send("GET", "/ingest/v1/messages?env=prod", key.secret(), null).statusCode()).isEqualTo(400);

        for (String bad : List.of(
                "{\"env\":\"dev\",\"appId\":\"../../x\",\"type\":\"heartbeat\"}",
                "{\"env\":\"dev\",\"appId\":\"shop\",\"id\":\"../x\",\"type\":\"heartbeat\"}",
                "{\"env\":\"dev\",\"appId\":\"shop\"}",
                "{\"env\":\"../dev\",\"appId\":\"shop\",\"type\":\"heartbeat\"}")) {
            assertThat(send("POST", "/ingest/v1/messages", key.secret(), bad).statusCode()).as(bad).isEqualTo(400);
        }
        try (var files = Files.walk(MESSAGES)) {
            assertThat(files.filter(Files::isRegularFile)).allMatch(f -> f.startsWith(MESSAGES.resolve(key.domain())));
        }
    }

    // a key as the backend creates it (kockpit-backend-tenants)
    private Key key(String slug, List<String> envs, int quota) {
        UUID user = UUID.randomUUID();
        jdbc.sql("insert into users (id, email, password_hash) values (:id, :email, 'x')")
                .param("id", user).param("email", slug + "@example.com").update();
        UUID tenant = UUID.randomUUID();
        jdbc.sql("insert into tenants (id, slug, owner_id) values (:id, :slug, :owner)")
                .param("id", tenant).param("slug", slug).param("owner", user).update();
        envs.forEach(env -> jdbc.sql("insert into tenant_envs (tenant_id, env) values (:id, :env)")
                .param("id", tenant).param("env", env).update());
        String secret = "kpk_" + UUID.randomUUID().toString().replace("-", "") + "abcdefghijk";
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into api_keys (id, tenant_id, name, prefix, key_hash, daily_quota)
                        values (:id, :tenant, 'test', :prefix, :hash, :quota)""")
                .param("id", id).param("tenant", tenant).param("prefix", secret.substring(0, 12))
                .param("hash", ApiKeyStore.sha256(secret)).param("quota", quota).update();
        return new Key(id, slug, secret);
    }

    private record Key(UUID id, String domain, String secret) {
    }

    private long usedToday(Key key) {
        return jdbc.sql("select coalesce(sum(audits), 0) from usage_daily where api_key_id = :id and day = :day")
                .param("id", key.id()).param("day", LocalDate.now(ZoneOffset.UTC)).query(Long.class).single();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private HttpResponse<String> postAudits(String key, String body) throws Exception {
        return send("POST", "/ingest/v1/audits", key, body);
    }

    private HttpResponse<String> send(String method, String path, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (key != null) {
            request.header("X-Kockpit-Api-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}

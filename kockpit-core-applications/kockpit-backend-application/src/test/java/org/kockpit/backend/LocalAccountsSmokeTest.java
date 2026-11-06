package org.kockpit.backend;

import org.junit.jupiter.api.Test;
import org.kockpit.features.manifest.services.backend.ManifestAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.web.SecurityFilterChain;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole backend with local accounts on (default Maven profile, filesystem storage): one
 * filter chain (the local one), access control on by default, signup then an API call, then a
 * tenant whose manifest is stored and whose domain gets polled right away.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "FS_STORAGE_PATH=target/smoke-local-data",
        "FS_MANIFESTS_PATH=target/smoke-local-manifests",
        "STORAGE_ACCOUNT=devstoreaccount1",
        "STORAGE_KEY=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==",
        "MANIFESTS_STORAGE_ACCOUNT=devstoreaccount1",
        "MANIFESTS_STORAGE_KEY=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==",
        "kockpit.aws.s3.bucket=smoke-test-bucket",
        "kockpit.manifests.aws.region=eu-west-1",
        "kockpit.aws.region=eu-west-1",
        "kockpit.audit.notification.kinesis.stream_name=smoke-test",
        "kockpit.audit.notification.eventhub.event_hub_name=smoke-test",
        "kockpit.audit.notification.eventhub.connection_string=Endpoint=sb://smoke-test.servicebus.windows.net/;SharedAccessKeyName=smoke;SharedAccessKey=c21va2U=",
        "KOCKPIT_LOCAL_AUTH_ENABLED=true",
        "KOCKPIT_JWT_SECRET=smoke-secret-smoke-secret-smoke-secret-01",
})
@Testcontainers
class LocalAccountsSmokeTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("KOCKPIT_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("KOCKPIT_DB_USERNAME", POSTGRES::getUsername);
        registry.add("KOCKPIT_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    List<SecurityFilterChain> filterChains;

    @Autowired
    ManifestAccessService manifestAccessService;

    @Autowired
    ManifestPollers manifestPollers;

    @LocalServerPort
    int port;

    @Test
    void local_accounts_replace_the_default_chain_and_turn_access_control_on() throws Exception {
        assertThat(filterChains).hasSize(1);
        assertThat(manifestAccessService.isEnabled()).isTrue();

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(uri("/api/manifests")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        HttpResponse<String> signup = http.send(HttpRequest.newBuilder(uri("/api/auth/signup"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"smoke@example.com\",\"password\":\"smoke-password\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(signup.statusCode()).isEqualTo(201);
        String cookie = signup.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

        HttpResponse<String> me = http.send(HttpRequest.newBuilder(uri("/api/me")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("smoke@example.com");

        // the manifests directory outlives the run: a new name each time
        String slug = "smoke" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpResponse<String> tenant = http.send(HttpRequest.newBuilder(uri("/api/tenants"))
                        .header("Content-Type", "application/json").header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"slug\":\"" + slug + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tenant.statusCode()).isEqualTo(201);
        assertThat(manifestPollers.started()).contains(slug + "|dev|heartbeat", slug + "|dev|audit");
        HttpResponse<String> manifests = http.send(HttpRequest.newBuilder(uri("/api/manifests")).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(manifests.body()).contains("tenant-" + slug + "-dev.json");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}

package org.kockpit.backend.security.local;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local accounts end to end: real HTTP (cookies included) against the security package as the
 * backend wires it, PostgreSQL in a container, schema from the Flyway migrations.
 */
@SpringBootTest(classes = LocalAuthTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "kockpit.security.local.enabled=true",
                "kockpit.security.local.jwt-secret=test-secret-test-secret-test-secret-0123",
                "kockpit.security.local.signup.per-ip-per-hour=1000",
                "kockpit.security.local.login.max-failures-per-email=3",
                "kockpit.security.local.admin.email=Admin@Example.com",
                "kockpit.security.local.admin.password=admin-password-123",
        })
@Testcontainers
class LocalAuthIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("kockpit.security.local.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("kockpit.security.local.datasource.username", POSTGRES::getUsername);
        registry.add("kockpit.security.local.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void signup_sets_a_secure_cookie_that_authenticates_the_api() throws Exception {
        String email = uniqueEmail();

        HttpResponse<String> signup = post("/auth/signup", credentials(email, "a-good-password"), null);

        assertThat(signup.statusCode()).isEqualTo(201);
        assertThat(signup.body()).contains("\"username\":\"" + email + "\"");
        String setCookie = signup.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).startsWith("id_token=").contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age=28800");

        String cookie = cookieOf(signup);
        assertThat(get("/me", cookie).body()).contains(email);
        assertThat(get("/insurance/dev/audits/_search", cookie).body()).isEqualTo("audits for " + email);
        // the bearer header works too (API clients)
        HttpResponse<String> bearer = http.send(HttpRequest.newBuilder(uri("/me"))
                .header("Authorization", "Bearer " + cookie.substring("id_token=".length())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bearer.statusCode()).isEqualTo(200);
    }

    @Test
    void the_api_needs_a_valid_token() throws Exception {
        assertThat(get("/insurance/dev/audits/_search", null).statusCode()).isEqualTo(401);
        assertThat(get("/me", "id_token=not-a-jwt").statusCode()).isEqualTo(401);

        // signed with another secret
        LocalTokenService other = new LocalTokenService("another-secret-another-secret-another-0123", Duration.ofHours(1));
        LocalUser user = signedUp(uniqueEmail());
        assertThat(get("/me", "id_token=" + other.issue(user)).statusCode()).isEqualTo(401);

        // expired
        // issued 2 h ago for 1 h (well past the decoder's 60 s clock skew)
        LocalTokenService sameSecretExpired = new LocalTokenService("test-secret-test-secret-test-secret-0123", Duration.ofHours(1),
                Clock.offset(Clock.systemUTC(), Duration.ofHours(-2)));
        assertThat(get("/me", "id_token=" + sameSecretExpired.issue(user)).statusCode()).isEqualTo(401);
    }

    @Test
    void login_checks_the_password_and_emails_are_case_insensitive() throws Exception {
        String email = uniqueEmail();
        post("/auth/signup", credentials(email, "a-good-password"), null);

        assertThat(post("/auth/login", credentials(email, "wrong-password"), null).statusCode()).isEqualTo(401);
        assertThat(post("/auth/login", credentials("nobody-" + email, "a-good-password"), null).statusCode()).isEqualTo(401);

        HttpResponse<String> login = post("/auth/login", credentials(email.toUpperCase(), "a-good-password"), null);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(get("/me", cookieOf(login)).body()).contains(email);

        assertThat(post("/auth/signup", credentials(email.toUpperCase(), "another-password"), null).statusCode()).isEqualTo(409);
    }

    @Test
    void a_stale_cookie_does_not_block_login() throws Exception {
        String email = uniqueEmail();
        post("/auth/signup", credentials(email, "a-good-password"), null);

        assertThat(post("/auth/login", credentials(email, "a-good-password"), "id_token=expired-or-garbage").statusCode())
                .isEqualTo(200);
    }

    @Test
    void signup_validates_email_and_password() throws Exception {
        assertThat(post("/auth/signup", credentials("not-an-email", "a-good-password"), null).statusCode()).isEqualTo(400);
        assertThat(post("/auth/signup", credentials(uniqueEmail(), "short"), null).statusCode()).isEqualTo(400);
        assertThat(post("/auth/signup", credentials(uniqueEmail(), "x".repeat(73)), null).statusCode()).isEqualTo(400);
        assertThat(post("/auth/signup", "{}", null).statusCode()).isEqualTo(400);
    }

    @Test
    void repeated_failures_lock_the_email_out() throws Exception {
        String email = uniqueEmail();
        post("/auth/signup", credentials(email, "a-good-password"), null);

        for (int i = 0; i < 3; i++) {
            assertThat(post("/auth/login", credentials(email, "wrong-password"), null).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> locked = post("/auth/login", credentials(email, "a-good-password"), null);
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).hasValue("900");
    }

    @Test
    void groups_and_disabling_apply_without_a_new_login() throws Exception {
        String email = uniqueEmail();
        HttpResponse<String> signup = post("/auth/signup", credentials(email, "a-good-password"), null);
        String cookie = cookieOf(signup);
        LocalUser user = users.findByEmail(email).orElseThrow();

        users.addGroup(user.id(), "tenant:acme");
        assertThat(get("/me", cookie).body()).contains("tenant:acme");

        users.setEnabled(user.id(), false);
        assertThat(get("/me", cookie).statusCode()).isEqualTo(401);
        assertThat(post("/auth/login", credentials(email, "a-good-password"), null).statusCode()).isEqualTo(401);
    }

    @Test
    void the_admin_account_is_created_at_startup_in_the_admin_group() throws Exception {
        HttpResponse<String> login = post("/auth/login", credentials("admin@example.com", "admin-password-123"), null);

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("kockpit-admins");
    }

    @Test
    void logout_clears_the_cookie() throws Exception {
        HttpResponse<String> logout = post("/auth/logout", "", null);

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("Set-Cookie").orElseThrow()).startsWith("id_token=;").contains("Max-Age=0");
    }

    private LocalUser signedUp(String email) throws Exception {
        post("/auth/signup", credentials(email, "a-good-password"), null);
        return users.findByEmail(email).orElseThrow();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String cookieOf(HttpResponse<?> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> post(String path, String json, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}

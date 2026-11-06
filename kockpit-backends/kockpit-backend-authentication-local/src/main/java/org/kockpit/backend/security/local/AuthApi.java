package org.kockpit.backend.security.local;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.backend.security.SecurityConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Signup, login and logout for local accounts. Signup and login answer like {@code GET /me}
 * ({@code username}, {@code groups}) and set the {@code id_token} cookie.
 */
@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(name = "kockpit.security.local.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AuthApi {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private static final int MAX_EMAIL_LENGTH = 254;

    // BCrypt only uses the first 72 bytes of a password: refuse longer ones rather than truncate
    private static final int MAX_PASSWORD_BYTES = 72;

    private static final Duration HOUR = Duration.ofHours(1);

    private final UserRepository users;

    private final PasswordEncoder passwordEncoder;

    private final LocalTokenService tokens;

    private final AttemptLimiter limiter;

    private final LocalAuthProperties properties;

    // compared against when the email is unknown, so a login takes as long either way
    private volatile String dummyHash;

    public record Credentials(String email, String password) {
    }

    @PostMapping("/signup")
    ResponseEntity<?> signup(@RequestBody Credentials credentials, HttpServletRequest request) {
        LocalAuthProperties.Signup signup = properties.getSignup();
        if (!signup.isEnabled()) {
            return error(HttpStatus.FORBIDDEN, "signup_disabled", "Signup is disabled on this instance");
        }
        Optional<String> invalid = validate(credentials);
        if (invalid.isPresent()) {
            return error(HttpStatus.BAD_REQUEST, "bad_request", invalid.get());
        }
        if (!limiter.tryAcquire("signup-ip:" + request.getRemoteAddr(), signup.getPerIpPerHour(), HOUR)
                || !limiter.tryAcquire("signup-all", signup.getPerHour(), HOUR)) {
            return tooMany(HOUR);
        }
        Optional<LocalUser> created = users.create(credentials.email(), passwordEncoder.encode(credentials.password()));
        if (created.isEmpty()) {
            return error(HttpStatus.CONFLICT, "email_taken", "An account already exists for this email");
        }
        log.info("Local account created: {}", created.get().email());
        return loggedIn(HttpStatus.CREATED, created.get());
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        if (credentials == null || credentials.email() == null || credentials.password() == null) {
            return error(HttpStatus.BAD_REQUEST, "bad_request", "email and password are required");
        }
        LocalAuthProperties.Login login = properties.getLogin();
        String emailKey = "login-email:" + UserRepository.normalize(credentials.email());
        String ipKey = "login-ip:" + request.getRemoteAddr();
        if (limiter.isBlocked(emailKey, login.getMaxFailuresPerEmail()) || limiter.isBlocked(ipKey, login.getMaxFailuresPerIp())) {
            return tooMany(login.getLockout());
        }
        Optional<LocalUser> user = users.findByEmail(credentials.email());
        boolean matches = user
                .map(u -> passwordEncoder.matches(credentials.password(), u.passwordHash()))
                .orElseGet(() -> {
                    passwordEncoder.matches(credentials.password(), dummyHash());
                    return false;
                });
        if (!matches || !user.get().enabled()) {
            limiter.tryAcquire(emailKey, Integer.MAX_VALUE, login.getLockout());
            limiter.tryAcquire(ipKey, Integer.MAX_VALUE, login.getLockout());
            return error(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password");
        }
        limiter.reset(emailKey);
        users.touchLogin(user.get().id());
        return loggedIn(HttpStatus.OK, user.get());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    private Optional<String> validate(Credentials credentials) {
        if (credentials == null || credentials.email() == null || credentials.password() == null) {
            return Optional.of("email and password are required");
        }
        String email = credentials.email().trim();
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
            return Optional.of("Invalid email");
        }
        String password = credentials.password();
        if (password.length() < properties.getPasswordMinLength()) {
            return Optional.of("The password needs at least %d characters".formatted(properties.getPasswordMinLength()));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return Optional.of("The password is too long (72 bytes at most)");
        }
        return Optional.empty();
    }

    private ResponseEntity<Map<String, Object>> loggedIn(HttpStatus status, LocalUser user) {
        List<String> groups = users.groups(user.id());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(tokens.issue(user), tokens.ttl()).toString())
                .body(Map.of("username", user.email(), "groups", groups));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(SecurityConfig.ID_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode("kockpit-dummy-password");
        }
        return dummyHash;
    }

    private static ResponseEntity<Map<String, Object>> tooMany(Duration retryAfter) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()))
                .body(Map.of("error", "too_many_requests", "message", "Too many attempts, try again later"));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}

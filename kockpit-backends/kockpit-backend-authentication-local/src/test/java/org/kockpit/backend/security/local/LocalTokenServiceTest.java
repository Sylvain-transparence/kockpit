package org.kockpit.backend.security.local;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTokenServiceTest {

    @Test
    void refuses_short_secrets() {
        assertThatThrownBy(() -> new LocalTokenService("too-short", Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> new LocalTokenService(null, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issues_tokens_it_verifies() {
        LocalTokenService tokens = new LocalTokenService("0123456789abcdef0123456789abcdef", Duration.ofHours(1));
        LocalUser user = new LocalUser(UUID.randomUUID(), "jane@example.com", "hash", true);

        Jwt jwt = tokens.decoder().decode(tokens.issue(user));

        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("jane@example.com");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(LocalTokenService.ISSUER);
    }
}

package org.kockpit.backend.security.local;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Issues and verifies the login tokens: HS256 JWTs, subject = user id. They only identify the
 * user - groups and the enabled flag are read from the database on every request, so a new tenant
 * group or a disabled account applies at once, without a new login.
 */
public class LocalTokenService {

    static final String ISSUER = "kockpit-local";

    private static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;

    private final JwtDecoder decoder;

    private final Duration ttl;

    private final Clock clock;

    public LocalTokenService(String secret, Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    LocalTokenService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "kockpit.security.local.jwt-secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbusDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER)));
        this.decoder = nimbusDecoder;
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(LocalUser user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.id().toString())
                .claim("email", user.email())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public Duration ttl() {
        return ttl;
    }
}

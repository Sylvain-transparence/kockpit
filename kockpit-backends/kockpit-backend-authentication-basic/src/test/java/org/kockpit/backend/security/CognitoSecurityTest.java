package org.kockpit.backend.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CognitoSecurityTest {

    private static final String ISSUER = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_I04Zn8e82";
    private static final String CLIENT_ID = "3l0sphtgrivrp3bspafpd8q442";

    private final KeyPair keys = rsaKeys();

    @Test
    void maps_adgroups_of_a_real_cognito_id_token_to_authorities() {
        // Claims of an actual console ID token (signature replaced by a test key).
        Jwt jwt = decode(token(Map.of(
                "custom:adgroups", "KOCKPIT-Console-ReadOnly",
                "token_use", "id",
                "aud", CLIENT_ID,
                "email", "jane.doe@example.com")));

        var authentication = SecurityConfig.toAuthentication(jwt, CognitoGroups.DEFAULT_CLAIMS);

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("KOCKPIT-Console-ReadOnly");
        assertThat(authentication.getName()).isEqualTo("jane.doe@example.com");
    }

    @Test
    void parses_every_multi_group_shape_cognito_stores() {
        assertThat(CognitoGroups.of(jwt(Map.of("custom:adgroups", "A,B"))))
                .containsExactly("A", "B");
        assertThat(CognitoGroups.of(jwt(Map.of("custom:adgroups", "[A, B]"))))
                .containsExactly("A", "B");
        assertThat(CognitoGroups.of(jwt(Map.of("custom:adgroups", "[\"A\",\"B\"]"))))
                .containsExactly("A", "B");
        assertThat(CognitoGroups.of(jwt(Map.of("custom:adgroups", "A", "cognito:groups", List.of("C")))))
                .containsExactly("A", "C");
        assertThat(CognitoGroups.of(jwt(Map.of("sub", "x")))).isEmpty();
    }

    @Test
    void reads_groups_from_the_configured_claims_only() {
        Jwt jwt = jwt(Map.of("custom:adgroups", "AD-GROUP", "custom:teams", "[T1, T2]", "cognito:groups", List.of("C")));

        assertThat(CognitoGroups.of(jwt, CognitoGroups.parseClaims("custom:teams")))
                .containsExactly("T1", "T2");
        assertThat(CognitoGroups.of(jwt, CognitoGroups.parseClaims(" custom:teams , cognito:groups ")))
                .containsExactly("T1", "T2", "C");
        assertThat(CognitoGroups.parseClaims("")).isEqualTo(CognitoGroups.DEFAULT_CLAIMS);
        assertThat(CognitoGroups.of(jwt, CognitoGroups.parseClaims(null))).containsExactly("AD-GROUP", "C");
    }

    @Test
    void rejects_tokens_for_another_client_or_issuer_or_access_tokens() {
        assertThatThrownBy(() -> decode(token(Map.of("token_use", "id", "aud", "other-client"))))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decode(token(Map.of("token_use", "access", "aud", CLIENT_ID))))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decode(token(Map.of("token_use", "id", "aud", CLIENT_ID, "iss", "https://evil"))))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejects_expired_tokens() {
        assertThatThrownBy(() -> decode(token(Map.of("token_use", "id", "aud", CLIENT_ID,
                "iat", Instant.now().minusSeconds(7200), "exp", Instant.now().minusSeconds(3600)))))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void reads_the_token_from_the_bearer_header_or_the_id_token_cookie() {
        var resolver = SecurityConfig.bearerOrCookieTokenResolver();

        MockHttpServletRequest header = new MockHttpServletRequest();
        header.addHeader("Authorization", "Bearer from-header");
        header.setCookies(new Cookie(SecurityConfig.ID_TOKEN_COOKIE, "from-cookie"));
        assertThat(resolver.resolve(header)).isEqualTo("from-header");

        MockHttpServletRequest cookie = new MockHttpServletRequest();
        cookie.setCookies(new Cookie("other", "x"), new Cookie(SecurityConfig.ID_TOKEN_COOKIE, "from-cookie"));
        assertThat(resolver.resolve(cookie)).isEqualTo("from-cookie");

        assertThat(resolver.resolve(new MockHttpServletRequest())).isNull();
    }

    private Jwt decode(String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(SecurityConfig.cognitoValidator(ISSUER, CLIENT_ID));
        return decoder.decode(token);
    }

    private String token(Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject("cdc83534-06ad-4085-b505-2f46b5d1748a")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
            claims.forEach((name, value) -> builder.claim(name,
                    value instanceof Instant instant ? Date.from(instant) : value));
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }

    private static KeyPair rsaKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

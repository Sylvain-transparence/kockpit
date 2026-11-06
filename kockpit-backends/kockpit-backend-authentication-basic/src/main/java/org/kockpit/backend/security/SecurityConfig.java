package org.kockpit.backend.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    // The console's Cognito login (Lambda@Edge, lambda-auth.js) keeps the ID token in this cookie;
    // so does the local accounts login (kockpit-backend-authentication-local).
    public static final String ID_TOKEN_COOKIE = "id_token";

    @Value("${kockpit.username:user}")
    private String username;

    @Value("${kockpit.password:changeme}")
    private String password;

    // e.g. https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_I04Zn8e82 - when set, every API
    // call needs a valid Cognito ID token, and the user's groups drive manifest access.
    @Value("${kockpit.security.cognito.issuer-uri:}")
    private String cognitoIssuerUri;

    // The app client id the ID tokens are issued for ("aud"); not checked when empty.
    @Value("${kockpit.security.cognito.client-id:}")
    private String cognitoClientId;

    // Token claim(s) holding the user's groups, comma-separated; checked against the manifests'
    // groups/policies. Defaults to the SAML-mapped AD groups plus native Cognito groups.
    @Value("${kockpit.security.cognito.groups-claim:custom:adgroups,cognito:groups}")
    private String groupsClaim;

    // Not with local accounts (kockpit.security.local.enabled), whose module brings its own chain.
    @Bean
    @ConditionalOnProperty(name = "kockpit.security.local.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        if (!StringUtils.hasText(cognitoIssuerUri)) {
            log.warn("⚠️ No kockpit.security.cognito.issuer-uri: API is not authenticated and access by groups is off");
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
            return http.build();
        }

        List<String> groupsClaims = CognitoGroups.parseClaims(groupsClaim);
        log.info("✅ Cognito authentication on, issuer {}, client id {}, groups from {}", cognitoIssuerUri,
                StringUtils.hasText(cognitoClientId) ? cognitoClientId : "(not checked)", groupsClaims);
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerOrCookieTokenResolver())
                        .jwt(jwt -> jwt
                                .decoder(cognitoJwtDecoder(cognitoIssuerUri, cognitoClientId))
                                .jwtAuthenticationConverter(token -> toAuthentication(token, groupsClaims))));
        return http.build();
    }

    // The JWKS is fetched lazily on the first token (not at startup), so the app starts even if
    // Cognito is briefly unreachable.
    static JwtDecoder cognitoJwtDecoder(String issuerUri, String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(issuerUri.replaceAll("/+$", "") + "/.well-known/jwks.json")
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(cognitoValidator(issuerUri, clientId));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> cognitoValidator(String issuerUri, String clientId) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuerUri.replaceAll("/+$", "")));
        // custom:adgroups is only in ID tokens; an access token would authenticate with no groups.
        validators.add(new JwtClaimValidator<String>("token_use", "id"::equals));
        if (StringUtils.hasText(clientId)) {
            validators.add(new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.contains(clientId)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    // Authorities = the user's groups, as-is (ManifestAccessService reads them back as groups).
    static AbstractAuthenticationToken toAuthentication(Jwt jwt, List<String> groupsClaims) {
        List<SimpleGrantedAuthority> authorities = CognitoGroups.of(jwt, groupsClaims).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        String name = jwt.getClaimAsString("email") != null ? jwt.getClaimAsString("email") : jwt.getSubject();
        return new JwtAuthenticationToken(jwt, authorities, name);
    }

    // "Authorization: Bearer <id token>" first, else the console's id_token cookie.
    public static BearerTokenResolver bearerOrCookieTokenResolver() {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> {
            String token = header.resolve(request);
            if (token != null || request.getCookies() == null) {
                return token;
            }
            return Arrays.stream(request.getCookies())
                    .filter(cookie -> ID_TOKEN_COOKIE.equals(cookie.getName()))
                    .map(Cookie::getValue)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        };
    }

    // Origins (patterns) allowed to call the API from a browser with credentials, comma-separated.
    // "*" (the default) lets any site send the user's cookie along: set the console's origin(s)
    // (e.g. https://console.example.com) on any instance reachable from the internet.
    @Value("${kockpit.security.cors.allowed-origins:*}")
    private String corsAllowedOrigins;

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = parseOrigins(corsAllowedOrigins);
        if (origins.contains("*")) {
            log.warn("⚠️ CORS allows every origin with credentials (kockpit.security.cors.allowed-origins=*)");
        } else {
            log.info("✅ CORS allowed origins: {}", origins);
        }
        configuration.setAllowedOriginPatterns(origins);
        configuration.addAllowedHeader("*");
        configuration.setAllowedMethods(Arrays.asList("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    static List<String> parseOrigins(String configured) {
        if (!StringUtils.hasText(configured)) {
            return List.of("*");
        }
        List<String> origins = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        return origins.isEmpty() ? List.of("*") : origins;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService() {
        UserDetails user = User
                .withUsername(username)
                .password("{noop}"+password)
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(user);
    }
}

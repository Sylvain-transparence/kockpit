package org.kockpit.backend.security.local;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.kockpit.backend.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Local user accounts, for an instance without an identity provider (e.g. a public demo).
 * On with {@code kockpit.security.local.enabled=true}; it then replaces the Cognito / open
 * filter chain of {@link SecurityConfig}.
 *
 * <ul>
 *   <li>users and groups in PostgreSQL ({@code kockpit.security.local.datasource.*}), schema
 *       migrated by Flyway at startup (db/kockpit-accounts, own history table);</li>
 *   <li>{@code /auth/signup} and {@code /auth/login} set a backend-signed token in the same
 *       {@code id_token} cookie the Cognito login uses (HttpOnly, SameSite=Strict), so the
 *       console and the bearer/cookie resolution work unchanged;</li>
 *   <li>every API call but /auth/** and health/docs needs that token; the user's authorities are
 *       their groups, read from the database on each request, which manifest access control
 *       ({@code kockpit.security.access-control.*}, on by default with local accounts) checks.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "kockpit.security.local.enabled", havingValue = "true")
@EnableConfigurationProperties(LocalAuthProperties.class)
@Slf4j
public class LocalAuthConfiguration {

    @Bean(destroyMethod = "close")
    HikariDataSource kockpitAccountsDataSource(LocalAuthProperties properties) {
        LocalAuthProperties.Datasource ds = properties.getDatasource();
        HikariConfig config = new HikariConfig();
        config.setPoolName("kockpit-accounts");
        config.setJdbcUrl(ds.getUrl());
        config.setUsername(ds.getUsername());
        config.setPassword(ds.getPassword());
        config.setMaximumPoolSize(ds.getMaximumPoolSize());
        log.info("✅ Local accounts database: {} (user {})", ds.getUrl(), ds.getUsername());
        HikariDataSource dataSource = new HikariDataSource(config);
        migrate(dataSource);
        return dataSource;
    }

    static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/kockpit-accounts")
                .table("kockpit_accounts_schema_history")
                .load()
                .migrate();
    }

    @Bean
    UserRepository userRepository(HikariDataSource kockpitAccountsDataSource) {
        return new UserRepository(JdbcClient.create(kockpitAccountsDataSource));
    }

    @Bean
    LocalTokenService localTokenService(LocalAuthProperties properties) {
        return new LocalTokenService(properties.getJwtSecret(), properties.getTokenTtl());
    }

    @Bean
    AttemptLimiter attemptLimiter() {
        return new AttemptLimiter();
    }

    @Bean
    SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http,
            LocalTokenService tokens,
            UserRepository users,
            @Value("${kockpit.security.cognito.issuer-uri:}") String cognitoIssuerUri
    ) throws Exception {
        if (StringUtils.hasText(cognitoIssuerUri)) {
            log.warn("⚠️ Both local accounts and Cognito are configured: local accounts win, Cognito tokens are refused");
        }
        log.info("✅ Local accounts authentication on");
        http.sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // cookie auth without CSRF tokens: the cookie is SameSite=Strict, the API only takes
                // JSON (a cross-site form can't send it without a CORS preflight) and CORS is limited
                // to the console origin (kockpit.security.cors.allowed-origins)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers("/auth/signup", "/auth/login", "/auth/logout").permitAll()
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(skippingAuthEndpoints(SecurityConfig.bearerOrCookieTokenResolver()))
                        .jwt(jwt -> jwt
                                .decoder(tokens.decoder())
                                .jwtAuthenticationConverter(token -> toAuthentication(token, users))));
        return http.build();
    }

    // An expired or foreign id_token cookie must not turn /auth/login into a 401: the resource
    // server rejects a bad token even on permitAll paths, so none is read there.
    static BearerTokenResolver skippingAuthEndpoints(BearerTokenResolver resolver) {
        return (HttpServletRequest request) -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return path.startsWith("/auth/") ? null : resolver.resolve(request);
        };
    }

    static JwtAuthenticationToken toAuthentication(Jwt jwt, UserRepository users) {
        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new BadCredentialsException("Invalid token subject");
        }
        LocalUser user = users.findById(id).orElseThrow(() -> new BadCredentialsException("Unknown user"));
        if (!user.enabled()) {
            throw new DisabledException("Account disabled");
        }
        var authorities = users.groups(id).stream().map(SimpleGrantedAuthority::new).toList();
        return new JwtAuthenticationToken(jwt, authorities, user.email());
    }

    @Bean
    ApplicationRunner localAdminBootstrap(LocalAuthProperties properties, UserRepository users, PasswordEncoder passwordEncoder) {
        return args -> {
            LocalAuthProperties.Admin admin = properties.getAdmin();
            if (!StringUtils.hasText(admin.getEmail()) || !StringUtils.hasText(admin.getPassword())) {
                return;
            }
            LocalUser user = users.findByEmail(admin.getEmail())
                    .or(() -> users.create(admin.getEmail(), passwordEncoder.encode(admin.getPassword())))
                    .orElseThrow();
            users.addGroup(user.id(), admin.getGroup());
            log.info("✅ Local admin account {} in group {}", user.email(), admin.getGroup());
        };
    }
}

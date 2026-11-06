package org.kockpit.backend.tenants;

import com.zaxxer.hikari.HikariDataSource;
import org.kockpit.backend.security.local.AttemptLimiter;
import org.kockpit.backend.security.local.UserRepository;
import org.kockpit.features.manifest.services.backend.ManifestBackendService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Tenants and API keys, with local accounts (same database), see {@link TenantService}. */
@Configuration
@ConditionalOnProperty(name = "kockpit.security.local.enabled", havingValue = "true")
@EnableConfigurationProperties(TenantProperties.class)
public class TenantConfiguration {

    @Bean
    TenantRepository tenantRepository(HikariDataSource kockpitAccountsDataSource) {
        return new TenantRepository(JdbcClient.create(kockpitAccountsDataSource));
    }

    @Bean
    MembershipService membershipService(TenantRepository tenants, UserRepository users,
                                        HikariDataSource kockpitAccountsDataSource, TenantProperties properties,
                                        AttemptLimiter attemptLimiter) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(kockpitAccountsDataSource));
        return new MembershipService(tenants, new MembershipRepository(JdbcClient.create(kockpitAccountsDataSource)),
                users, transaction, properties, attemptLimiter, Clock.systemUTC());
    }

    @Bean
    TenantService tenantService(TenantRepository tenants, UserRepository users, ManifestBackendService manifests,
                                HikariDataSource kockpitAccountsDataSource, TenantProperties properties,
                                ResourceLoader resourceLoader) {
        // its own transaction manager: not a bean, so it can't be mistaken for the application's
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(kockpitAccountsDataSource));
        return new TenantService(tenants, users, manifests, transaction, properties, resourceLoader, Clock.systemUTC());
    }
}

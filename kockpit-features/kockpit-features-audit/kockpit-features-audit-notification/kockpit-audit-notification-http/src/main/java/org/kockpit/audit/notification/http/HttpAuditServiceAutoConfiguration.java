package org.kockpit.audit.notification.http;

import org.kockpit.audit.api.AuditReportNotificationService;
import org.kockpit.gateway.client.GatewayClient;
import org.kockpit.gateway.client.GatewayClientAutoConfiguration;
import org.kockpit.gateway.client.SendGate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Audits to the ingestion gateway: {@code kockpit.gateway.url} and {@code kockpit.gateway.api-key},
 * see {@link org.kockpit.gateway.client.GatewayClientProperties}.
 */
@AutoConfiguration(after = GatewayClientAutoConfiguration.class)
@ConditionalOnProperty(value = "kockpit.audit.http.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HttpAuditProperties.class)
public class HttpAuditServiceAutoConfiguration {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HttpAuditServiceAutoConfiguration.class);

    @Bean
    AuditReportNotificationService httpAuditReportNotificationService(GatewayClient gatewayClient, HttpAuditProperties properties) {
        log.info("✅ Audits sent to the Kockpit gateway {}", gatewayClient.baseUri());
        return new HttpAuditReportNotificationService(gatewayClient, properties, new SendGate("audits"), Clock.systemUTC());
    }
}

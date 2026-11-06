package org.kockpit.gateway.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The gateway client, for the HTTP audit sender and the HTTP messages transport. Both are only on
 * the classpath through their starters, so a missing {@code kockpit.gateway.url} or
 * {@code kockpit.gateway.api-key} fails the startup with a message saying so.
 */
@AutoConfiguration
@EnableConfigurationProperties(GatewayClientProperties.class)
public class GatewayClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GatewayClient kockpitGatewayClient(GatewayClientProperties properties) {
        return new GatewayClient(properties.getUrl(), properties.getApiKey(),
                properties.getConnectTimeout(), properties.getRequestTimeout());
    }
}

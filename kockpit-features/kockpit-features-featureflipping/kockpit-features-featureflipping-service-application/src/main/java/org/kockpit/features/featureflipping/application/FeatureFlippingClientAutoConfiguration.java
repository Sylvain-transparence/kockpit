package org.kockpit.features.featureflipping.application;

import org.kockpit.communication.MessageJson;
import org.kockpit.communication.Publisher;
import org.kockpit.sdk.SdkApplicationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.InetAddress;
import java.util.List;

import static java.util.Objects.isNull;

@AutoConfiguration
@EnableConfigurationProperties(SdkApplicationProperties.class)
@ConditionalOnProperty(
        value = "kockpit.feature-flipping.client.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FeatureFlippingClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FeatureFlippingClient featureFlippingClient(
            List<Publisher> publishers,
            SdkApplicationProperties applicationProperties,
            @Value("${kockpit.feature-flipping.ack.enabled:true}") boolean ackEnabled,
            // same instance id as the heartbeat, so the backend can match acknowledgements with live instances
            @Value("${kockpit.hostname:#{null}}") String hostname
    ) {
        return new FeatureFlippingClient(publishers, MessageJson.mapper(),
                applicationProperties.getDomain(), applicationProperties.getEnv(), applicationProperties.getAppId(),
                isNull(hostname) ? resolveHostname() : hostname, ackEnabled);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "UNKNOWN-" + Math.random();
        }
    }
}

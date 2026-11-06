package org.kockpit.communication.http;

import org.kockpit.communication.Consumer;
import org.kockpit.communication.Publisher;
import org.kockpit.gateway.client.GatewayClient;
import org.kockpit.gateway.client.GatewayClientAutoConfiguration;
import org.kockpit.gateway.client.SendGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Messages through the ingestion gateway: {@code kockpit.gateway.url} and
 * {@code kockpit.gateway.api-key}, see {@link org.kockpit.gateway.client.GatewayClientProperties}.
 */
@AutoConfiguration(after = GatewayClientAutoConfiguration.class)
@ConditionalOnProperty(value = "kockpit.communication.http.enabled", havingValue = "true", matchIfMissing = true)
public class HttpCommunicationAutoConfiguration {

    // one pause for publishing and polling: a rejected key stops both, logged once
    private final SendGate gate = new SendGate("messages");

    @Bean
    Publisher httpPublisher(GatewayClient gatewayClient,
                            @Value("${kockpit.communication.http.rejected-key-pause:PT5M}") Duration rejectedKeyPause) {
        return new HttpPublisher(gatewayClient, gate, rejectedKeyPause);
    }

    @Bean
    Consumer httpConsumer(GatewayClient gatewayClient,
                          @Value("${kockpit.communication.http.rejected-key-pause:PT5M}") Duration rejectedKeyPause) {
        return new HttpConsumer(gatewayClient, gate, rejectedKeyPause);
    }
}

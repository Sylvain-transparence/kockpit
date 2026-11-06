package org.kockpit.communication.http;

import org.kockpit.communication.Message;
import org.kockpit.communication.MessageJson;
import org.kockpit.communication.Publisher;
import org.kockpit.gateway.client.GatewayClient;
import org.kockpit.gateway.client.GatewayResponse;
import org.kockpit.gateway.client.SendGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Publishes a message (heartbeat, feature flipping acknowledgement) to the gateway, which keeps
 * it for the backend. The gateway sets the domain from the API key and checks the env.
 *
 * <p>Never throws: a lost heartbeat is replaced by the next one. Over the gateway's hourly limit
 * (429) or with a rejected key (401) it stops publishing for a while and says so once.
 */
public class HttpPublisher implements Publisher {

    static final String PATH = "/ingest/v1/messages";

    private static final Logger log = LoggerFactory.getLogger(HttpPublisher.class);

    private final GatewayClient client;

    private final SendGate gate;

    private final Duration rejectedKeyPause;

    public HttpPublisher(GatewayClient client, SendGate gate, Duration rejectedKeyPause) {
        this.client = client;
        this.gate = gate;
        this.rejectedKeyPause = rejectedKeyPause;
    }

    @Override
    public void publish(Message message) {
        if (!gate.isOpen()) {
            gate.drop(1);
            return;
        }
        try {
            GatewayResponse response = client.postJson(PATH, MessageJson.mapper().writeValueAsBytes(message), false);
            if (response.isSuccess()) {
                return;
            }
            String reason = GatewayClient.errorMessage(response).orElse("HTTP " + response.status());
            switch (response.status()) {
                case 429 -> gate.close(response.retryAfter().orElse(Duration.ofMinutes(5)), "message limit reached: " + reason);
                case 401, 403 -> gate.close(rejectedKeyPause, "API key rejected: " + reason);
                default -> log.warn("Kockpit gateway refused message {} of service {} (HTTP {}): {}",
                        message.getId(), message.getService(), response.status(), reason);
            }
        } catch (IOException e) {
            log.warn("Kockpit gateway {} unreachable, message {} of service {} lost: {}",
                    client.baseUri(), message.getId(), message.getService(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Cannot publish message {} to the Kockpit gateway", message.getId(), e);
        }
    }
}

package org.kockpit.communication.http;

import org.kockpit.communication.Consumer;
import org.kockpit.communication.Message;
import org.kockpit.communication.MessageJson;
import org.kockpit.gateway.client.GatewayClient;
import org.kockpit.gateway.client.GatewayResponse;
import org.kockpit.gateway.client.SendGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Polls the gateway for the messages the backend published to this application (feature
 * flipping, dynamic configuration). The {@code type} argument of {@link Consumer#poll} is the
 * service name the pollers pass (feature-flipping, dyna-config), sent as the {@code service}
 * filter; the domain is the API key's, whatever is asked.
 *
 * <p>Returned oldest first, like the other consumers: listeners apply them in that order, so
 * the last applied is the newest. A failed poll returns nothing and the next one catches up.
 * With a rejected key it shares the publisher's pause: no polling, one log line.
 */
public class HttpConsumer implements Consumer {

    private static final Logger log = LoggerFactory.getLogger(HttpConsumer.class);

    private final GatewayClient client;

    private final SendGate gate;

    private final Duration rejectedKeyPause;

    public HttpConsumer(GatewayClient client, SendGate gate, Duration rejectedKeyPause) {
        this.client = client;
        this.gate = gate;
        this.rejectedKeyPause = rejectedKeyPause;
    }

    @Override
    public List<Message> poll(String domain, String env, String appId, String service) {
        if (!gate.isOpen()) {
            return List.of();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("env", env);
        query.put("appId", appId);
        query.put("service", service);
        try {
            GatewayResponse response = client.get(HttpPublisher.PATH, query);
            if (response.status() == 401 || response.status() == 403) {
                gate.close(rejectedKeyPause, "API key rejected: " + GatewayClient.errorMessage(response).orElse("HTTP " + response.status()));
                return List.of();
            }
            if (!response.isSuccess()) {
                log.warn("Kockpit gateway refused to poll {} messages (HTTP {}): {}", service, response.status(),
                        GatewayClient.errorMessage(response).orElse(""));
                return List.of();
            }
            Message[] messages = MessageJson.mapper().readValue(response.body(), Message[].class);
            return Arrays.stream(messages)
                    .filter(Objects::nonNull)
                    .sorted(Message.BY_CREATION_DATE)
                    .toList();
        } catch (IOException e) {
            log.warn("Kockpit gateway {} unreachable, {} messages not polled: {}", client.baseUri(), service, e.toString());
        } catch (JacksonException e) {
            log.warn("Kockpit gateway answered {} messages that can't be read: {}", service, e.getOriginalMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }
}

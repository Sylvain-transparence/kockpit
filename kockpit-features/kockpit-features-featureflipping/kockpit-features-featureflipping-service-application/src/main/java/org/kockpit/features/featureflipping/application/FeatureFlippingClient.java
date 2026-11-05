package org.kockpit.features.featureflipping.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.communication.Message;
import org.kockpit.communication.Publisher;
import org.kockpit.core.sdk.OnMessageListener;
import org.kockpit.features.featureflipping.service.FeatureFlippingAckDto;
import org.kockpit.features.featureflipping.service.FeatureFlippingDto;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.kockpit.features.featureflipping.service.FeatureFlippingServiceDefinition.ACK;
import static org.kockpit.features.featureflipping.service.FeatureFlippingServiceDefinition.FEATURE_FLIPPING;

/**
 * Application side of feature flipping: keeps the flag values pushed by the backend (polled through the
 * communication layer) and acknowledges each new value, so the console shows which instances applied it.
 * A value is applied (and acknowledged) once per message: a republished value keeps its creationDate.
 */
@RequiredArgsConstructor
@Slf4j
public class FeatureFlippingClient implements OnMessageListener {

    private final List<Publisher> publishers;
    private final ObjectMapper objectMapper;
    private final String domain;
    private final String env;
    private final String appId;
    private final String instanceId;
    private final boolean ackEnabled;

    private final Map<String, Boolean> values = new ConcurrentHashMap<>();
    // key -> creationDate of the value message applied last
    private final Map<String, Long> appliedAt = new ConcurrentHashMap<>();

    public boolean isEnabled(String key) {
        return Boolean.TRUE.equals(values.get(key));
    }

    public Map<String, Boolean> getValues() {
        return Map.copyOf(values);
    }

    @Override
    public void onMessage(Message message) {
        if (!FEATURE_FLIPPING.equals(message.getService()) || ACK.equals(message.getType())) {
            return;
        }
        if (!Objects.equals(appId, message.getAppId())) {
            return; // another application's flag (same domain/env storage)
        }
        read(message).forEach((key, enabled) -> {
            Long last = appliedAt.get(key);
            if (last != null && last >= message.getCreationDate()) {
                return;
            }
            if (enabled == null) {
                values.remove(key);
            } else {
                values.put(key, enabled);
            }
            appliedAt.put(key, message.getCreationDate());
            log.info("feature flag {} = {} (value of {})", key, enabled, message.getCreationDate());
            if (ackEnabled) {
                acknowledge(key, enabled, message.getCreationDate());
            }
        });
    }

    private Map<String, Boolean> read(Message message) {
        Map<String, Boolean> read = new HashMap<>();
        if (message.getBody() != null) {
            FeatureFlippingDto flag = objectMapper.convertValue(message.getBody(), FeatureFlippingDto.class);
            if (flag != null && flag.getKey() != null) {
                read.put(flag.getKey(), flag.getEnabled());
            }
        } else if (message.getKeyValues() != null) {
            // messages written before the backend added the body only carry key/value
            message.getKeyValues().forEach(kv ->
                    read.put(kv.key(), kv.value() == null ? null : Boolean.valueOf(String.valueOf(kv.value()))));
        }
        return read;
    }

    private void acknowledge(String key, Boolean enabled, long valueCreationDate) {
        Message ack = Message.builder()
                // one message per flag and instance: instances of the same app do not overwrite each other
                .id("%s-ack-%s".formatted(key, instanceId))
                .service(FEATURE_FLIPPING)
                .type(ACK)
                .domain(domain)
                .env(env)
                .appId(appId)
                .creationDate(System.currentTimeMillis())
                .body(FeatureFlippingAckDto.builder()
                        .instanceId(instanceId)
                        .key(key)
                        .enabled(enabled)
                        .valueCreationDate(valueCreationDate)
                        .build())
                .build();
        publishers.forEach(publisher -> {
            try {
                publisher.publish(ack);
            } catch (Exception e) {
                log.warn("could not acknowledge feature flag {} through {}", key, publisher.getClass().getSimpleName(), e);
            }
        });
    }
}

package org.kockpit.features.featureflipping.services.backend;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.communication.KeyValue;
import org.kockpit.communication.Message;
import org.kockpit.communication.Publisher;
import org.kockpit.core.sdk.OnMessageListener;
import org.kockpit.features.featureflipping.service.FeatureFlippingDto;
import org.kockpit.features.featureflipping.service.FeatureFlippingInstanceDto;
import org.kockpit.features.featureflipping.service.FeatureFlippingInstanceDto.Status;
import org.kockpit.features.heartbeat.services.backend.HeartBeatManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.isNull;
import static org.kockpit.features.featureflipping.service.FeatureFlippingServiceDefinition.ACK;
import static org.kockpit.features.featureflipping.service.FeatureFlippingServiceDefinition.FEATURE_FLIPPING;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeatureFlippingService implements OnMessageListener {

    private final List<Publisher> publishers;

    private final HeartBeatManager heartBeatManager;

    private final Map<FeatureFlippingKey, FeatureFlippingDto> messagesCache = new ConcurrentHashMap<>();

    // flag -> instanceId -> last acknowledgement of that instance
    private final Map<FeatureFlippingKey, Map<String, Ack>> acks = new ConcurrentHashMap<>();

    public List<FeatureFlippingDto> get(String domain, String env, String appId) {
        Set<String> liveInstances = liveInstances(domain, env, appId);
        return messagesCache.entrySet().stream()
                .filter(entry -> {
                    FeatureFlippingKey key = entry.getKey();
                    return key.domain.equals(domain) && key.env.equals(env) && key.appId.equals(appId);
                })
                .map(entry -> withInstances(entry.getValue(), instances(entry.getKey(), entry.getValue(), liveInstances)))
                .toList();
    }

    public FeatureFlippingDto update(String domain, String env, String appId, FeatureFlippingDto featureFlippingDto) {
        // The console sends the manifest's flag (no domain/env/appId): republish() reads them from the cached dto.
        featureFlippingDto.setDomain(domain);
        featureFlippingDto.setEnv(env);
        featureFlippingDto.setAppId(appId);
        featureFlippingDto.setInstances(null);
        featureFlippingDto.setUpdatedAt(Instant.now().toEpochMilli());
        Message message = toMessage(domain, env, appId, featureFlippingDto);
        publishers.forEach(publisher -> {
            try  {
                publisher.publish(message);
                messagesCache.put(new FeatureFlippingKey(domain, env, appId, featureFlippingDto.getKey()), featureFlippingDto);
            } catch (Exception ex) {
                log.error("Error publishing message {}", message, ex);
            }
        });
        return featureFlippingDto;
    }

    private Message toMessage(String domain, String env, String appId, FeatureFlippingDto featureFlippingDto) {
        return Message.builder()
                .id(featureFlippingDto.getKey())
                .service(FEATURE_FLIPPING)
                .domain(domain)
                .env(env)
                .appId(appId)
                .body(featureFlippingDto)
                .keyValues(List.of(
                        new KeyValue(featureFlippingDto.getKey(), Objects.toString(featureFlippingDto.getEnabled(), null))
                ))
                // a republished value keeps the date of the change: instances apply and acknowledge it only once
                .creationDate(isNull(featureFlippingDto.getUpdatedAt()) ? Instant.now().toEpochMilli() : featureFlippingDto.getUpdatedAt())
                .build();
    }

    @Override
    public void onMessage(Message message) {
        if (!message.getService().equals(FEATURE_FLIPPING)) {
            return;
        }
        if (ACK.equals(message.getType())) {
            onAck(message);
            return;
        }
        if (message.getKeyValues() == null) {
            return;
        }
        message.getKeyValues().forEach(kv -> {
            FeatureFlippingKey flagKey = new FeatureFlippingKey(message.getDomain(), message.getEnv(), message.getAppId(), kv.key());
            FeatureFlippingDto cached = messagesCache.get(flagKey);
            boolean newer = cached != null && cached.getUpdatedAt() != null && message.getCreationDate() > cached.getUpdatedAt();
            if ("update".equals(message.getType()) ||
                    (isNull(message.getType()) && (cached == null || newer))
            ) {
                messagesCache.put(flagKey, FeatureFlippingDto
                        .builder()
                        .domain(message.getDomain())
                        .env(message.getEnv())
                        .appId(message.getAppId())
                        .key(kv.key())
                        // the stored value: without it a restarted backend served enabled=null, and the console
                        // fell back to the manifest's value
                        .enabled(toBoolean(kv.value()))
                        .comment(bodyString(message, "comment"))
                        .updatedAt(message.getCreationDate())
                        .build());
            }
        });
    }

    private void onAck(Message message) {
        if (!(message.getBody() instanceof Map<?, ?> body) || isNull(body.get("instanceId")) || isNull(body.get("key"))) {
            return;
        }
        FeatureFlippingKey flagKey = new FeatureFlippingKey(message.getDomain(), message.getEnv(), message.getAppId(), body.get("key").toString());
        Ack ack = new Ack(toBoolean(body.get("enabled")), toLong(body.get("valueCreationDate")), message.getCreationDate());
        acks.computeIfAbsent(flagKey, k -> new ConcurrentHashMap<>())
                .merge(body.get("instanceId").toString(), ack, (old, received) -> received.ackDate >= old.ackDate ? received : old);
    }

    private Set<String> liveInstances(String domain, String env, String appId) {
        Set<String> live = new TreeSet<>();
        heartBeatManager.get(domain, env).stream()
                .filter(heartbeat -> appId.equals(heartbeat.getAppId()))
                .forEach(heartbeat -> {
                    if (heartbeat.getBody() instanceof Map<?, ?> body && body.get("instanceId") != null) {
                        live.add(body.get("instanceId").toString());
                    }
                });
        return live;
    }

    private List<FeatureFlippingInstanceDto> instances(FeatureFlippingKey flagKey, FeatureFlippingDto flag, Set<String> liveInstances) {
        Map<String, Ack> flagAcks = acks.getOrDefault(flagKey, Map.of());
        // live instances (heartbeat); without heartbeats for this app, whatever acknowledged
        Set<String> instanceIds = new TreeSet<>(liveInstances.isEmpty() ? flagAcks.keySet() : liveInstances);
        return instanceIds.stream().map(instanceId -> {
            Ack ack = flagAcks.get(instanceId);
            if (ack == null) {
                return FeatureFlippingInstanceDto.builder().instanceId(instanceId).status(Status.WAITING).build();
            }
            boolean current = Objects.equals(ack.enabled, flag.getEnabled())
                    && (isNull(flag.getUpdatedAt()) || (ack.valueCreationDate != null && ack.valueCreationDate >= flag.getUpdatedAt()));
            return FeatureFlippingInstanceDto.builder()
                    .instanceId(instanceId)
                    .status(current ? Status.IN_SYNC : Status.OUTDATED)
                    .enabled(ack.enabled)
                    .valueCreationDate(ack.valueCreationDate)
                    .ackDate(ack.ackDate)
                    .build();
        }).toList();
    }

    private static FeatureFlippingDto withInstances(FeatureFlippingDto flag, List<FeatureFlippingInstanceDto> instances) {
        return FeatureFlippingDto.builder()
                .domain(flag.getDomain())
                .env(flag.getEnv())
                .appId(flag.getAppId())
                .key(flag.getKey())
                .expirationDate(flag.getExpirationDate())
                .enabled(flag.getEnabled())
                .comment(flag.getComment())
                .updatedAt(flag.getUpdatedAt())
                .instances(instances)
                .build();
    }

    private static Boolean toBoolean(Object value) {
        return value instanceof Boolean b ? b : isNull(value) ? null : Boolean.valueOf(value.toString());
    }

    private static Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : isNull(value) ? null : Long.valueOf(value.toString());
    }

    private static String bodyString(Message message, String field) {
        return message.getBody() instanceof Map<?, ?> body && body.get(field) instanceof String value ? value : null;
    }

    @Override
    public void republish() {
        messagesCache.values().forEach(flippingDto -> publishers.forEach(publisher -> {
            Message message = toMessage(flippingDto.getDomain(), flippingDto.getEnv(), flippingDto.getAppId(), flippingDto);
            publisher.publish(message);
        }));
    }

    private record Ack(Boolean enabled, Long valueCreationDate, long ackDate) {
    }

    @AllArgsConstructor
    @EqualsAndHashCode
    static class FeatureFlippingKey {
        String domain;
        String env;
        String appId;
        String key;
    }

    public List<FeatureFlippingDto> getHistory(String domain, String env) {
        return List.of();
    }
}

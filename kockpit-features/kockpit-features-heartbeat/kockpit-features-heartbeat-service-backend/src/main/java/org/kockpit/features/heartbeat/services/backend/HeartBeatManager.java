package org.kockpit.features.heartbeat.services.backend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.communication.Message;
import org.kockpit.core.sdk.OnMessageListener;
import org.kockpit.features.heartbeat.services.HeartBeatDto;
import org.kockpit.features.heartbeat.services.HeartBeatServiceDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class HeartBeatManager implements OnMessageListener {

    private final ApplicationEventPublisher applicationEventPublisher;

    // instanceKey -> lastSeenMs; evicted after instanceTtl
    private final Map<String, Message> instancesCache = new ConcurrentHashMap<>();

    @Value("${kockpit.communication.poller.instance-ttl:PT5M}")
    private Duration instanceTtl;

    public List<Message> get(String domain, String env) {
        return instancesCache.values().stream()
                .filter(message -> message.getDomain().equals(domain) &&  message.getEnv().equals(env))
                .sorted(Message.BY_CREATION_DATE)
                .toList();
    }

    @Override
    public void onMessage(Message message) {
        if (!message.getService().equals(HeartBeatServiceDefinition.SERVICE_NAME)) {
            return;
        }
        long now = System.currentTimeMillis();
        instancesCache.entrySet()
                .removeIf(e -> now - e.getValue().getCreationDate() > instanceTtl.toMillis());

        String key = instanceKey(message);

        // heartbeat files left behind by stopped instances are polled again and again: not alive
        if (now - message.getCreationDate() > instanceTtl.toMillis()) {
            instancesCache.remove(key);
            return;
        }

        if (isEnded(message)) {
            instancesCache.remove(key);
            log.trace("instance ended, removed from cache: {}", key);
            return;
        }

        instancesCache.put(key, message);
        log.trace("new/returned instance detected for {}", key);
        applicationEventPublisher.publishEvent(
                HeartBeatDto.builder()
                        .appId(message.getAppId())
                        .ended(false)
                        .build()
        );
    }

    private boolean isEnded(Message message) {
        if (message.getBody() instanceof Map<?, ?> body) {
            Object ended = body.get("ended");
            return Boolean.TRUE.equals(ended) || "true".equalsIgnoreCase(String.valueOf(ended));
        }
        return false;
    }

    private String instanceKey(Message message) {
        String instanceId = null;
        if (message.getBody() instanceof Map<?, ?> body) {
            Object id = body.get("instanceId");
            if (id != null) instanceId = id.toString();
        }
        return message.getDomain() + ":" + message.getEnv() + ":" + message.getAppId()
                + ":" + (instanceId != null ? instanceId : message.getAppId());
    }
}

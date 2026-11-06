package org.kockpit.backend;

import lombok.extern.slf4j.Slf4j;
import org.kockpit.communication.Consumer;
import org.kockpit.communication.polling.MessagePoller;
import org.kockpit.core.sdk.OnMessageListener;
import org.kockpit.features.manifest.services.backend.ManifestBackendService;
import org.kockpit.features.manifest.services.backend.ManifestSavedEvent;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.kockpit.features.manifest.services.dto.ServiceDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls the messages of every domain/env of the manifests: heartbeats, plus one poller per
 * service type. Pollers are started at startup and for any domain/env/type that appears later:
 * right after a manifest is saved on this replica (console upload, tenant creation), and by a
 * periodic rescan for manifests saved elsewhere (another replica, the storage directly).
 * A poller is never stopped: a removed manifest keeps its pollers until the next restart.
 */
@Component
@Slf4j
public class ManifestPollers {

    static final String HEARTBEAT = "heartbeat";

    private final List<Consumer> consumers;
    private final TaskScheduler taskScheduler;
    private final List<OnMessageListener> onMessageListeners;
    private final ManifestBackendService manifestBackendService;
    private final Duration pollingPeriod;

    // "domain|env|type" of the pollers started
    private final Set<String> started = ConcurrentHashMap.newKeySet();

    public ManifestPollers(
            List<Consumer> consumers,
            TaskScheduler taskScheduler,
            List<OnMessageListener> onMessageListeners,
            ManifestBackendService manifestBackendService,
            @Value("${kockpit.backend.heartbeat.poller.scheduling:PT1M}") String clientScheduling
    ) {
        this.consumers = consumers;
        this.taskScheduler = taskScheduler;
        this.onMessageListeners = onMessageListeners;
        this.manifestBackendService = manifestBackendService;
        this.pollingPeriod = Duration.parse(clientScheduling);
    }

    /** Starts the pollers at startup, then rescans the manifests every polling period. */
    public void start() {
        startMissing();
        taskScheduler.scheduleWithFixedDelay(this::rescan,
                Instant.now().plus(pollingPeriod), pollingPeriod);
    }

    @EventListener
    public void onManifestSaved(ManifestSavedEvent event) {
        startMissing();
    }

    private void rescan() {
        try {
            startMissing();
        } catch (Exception e) {
            log.warn("Could not rescan the manifests for new domains/envs", e);
        }
    }

    synchronized void startMissing() {
        for (ManifestDto manifest : manifestBackendService.list()) {
            if (manifest.getDomain() == null || manifest.getEnv() == null) {
                continue;
            }
            Set<String> types = new LinkedHashSet<>();
            types.add(HEARTBEAT);
            if (manifest.getServices() != null) {
                manifest.getServices().stream()
                        .map(ServiceDto::getType)
                        .filter(Objects::nonNull)
                        .forEach(types::add);
            }
            for (String type : types) {
                if (started.add(manifest.getDomain() + "|" + manifest.getEnv() + "|" + type)) {
                    log.info("Polling {} messages of {}/{}", type, manifest.getDomain(), manifest.getEnv());
                    new MessagePoller(consumers, taskScheduler, () -> type, onMessageListeners)
                            .start(manifest.getDomain(), manifest.getEnv(), null, pollingPeriod);
                }
            }
        }
    }

    Set<String> started() {
        return Set.copyOf(started);
    }
}

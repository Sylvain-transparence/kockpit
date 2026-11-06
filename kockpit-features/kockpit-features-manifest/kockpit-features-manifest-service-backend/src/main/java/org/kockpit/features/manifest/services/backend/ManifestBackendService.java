package org.kockpit.features.manifest.services.backend;

import lombok.extern.slf4j.Slf4j;
import org.kockpit.features.manifest.services.ManifestBackendRepository;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class ManifestBackendService implements InitializingBean, DisposableBean {

    // Access checks run on every API request and the console loads the list on every page, while
    // the S3 repository re-reads every manifest object on each findAll() (1-3s for ~90 manifests)
    // - so both use a copy, reloaded in the background every refresh period: no request waits for
    // a reload. A manifest change (new group/policy) applies within a period; save() drops the
    // copy immediately. Callers share the copy: they must not modify the returned manifests.
    static final Duration DEFAULT_REFRESH = Duration.ofSeconds(30);

    private final ManifestBackendRepository manifestBackendRepository;

    private final Duration refresh;

    private volatile CachedList cached;

    // bumped by save(): a load that began before it must not put back the list it read
    private final AtomicLong generation = new AtomicLong();

    private ScheduledExecutorService refresher;

    private ApplicationEventPublisher eventPublisher;

    @Autowired
    public ManifestBackendService(
            ManifestBackendRepository manifestBackendRepository,
            @Value("${kockpit.manifests.cache.refresh:30s}") Duration refresh
    ) {
        if (refresh == null || refresh.isZero() || refresh.isNegative()) {
            throw new IllegalArgumentException("kockpit.manifests.cache.refresh must be positive, got " + refresh);
        }
        this.manifestBackendRepository = manifestBackendRepository;
        this.refresh = refresh;
    }

    public ManifestBackendService(ManifestBackendRepository manifestBackendRepository) {
        this(manifestBackendRepository, DEFAULT_REFRESH);
    }

    @Autowired(required = false)
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void afterPropertiesSet() {
        // its own thread: a reload takes seconds, which would hold up the shared @Scheduled one
        refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "manifests-refresh");
            thread.setDaemon(true);
            return thread;
        });
        refresher.scheduleWithFixedDelay(this::refresh, 0, refresh.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Manifest list reloaded in the background every {}", refresh);
    }

    @Override
    public void destroy() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
    }

    public List<ManifestDto> list() {
        CachedList current = cached;
        // Loaded here only before the first background load, after a save(), or when the
        // background reloads stopped succeeding - so the copy is never more than two periods old.
        if (current == null || System.nanoTime() - current.loadedAt() > 2 * refresh.toNanos()) {
            current = load();
        }
        return current.manifests();
    }

    private void refresh() {
        try {
            load();
        } catch (Exception e) {
            // keep serving the previous copy; list() reloads itself once it is too old
            log.warn("Could not reload the manifests, keeping the previous list", e);
        }
    }

    private CachedList load() {
        long startedAt = generation.get();
        CachedList loaded = new CachedList(manifestBackendRepository.findAll(), System.nanoTime());
        if (generation.get() == startedAt) {
            cached = loaded;
        }
        return loaded;
    }

    public Optional<ManifestDto> get(String name) {
        return manifestBackendRepository.findByName(name);
    }

    /**
     * Stores the manifest, no access check: {@link ManifestApi} checks the caller first, tenant
     * provisioning saves the manifests it generates.
     */
    public ManifestDto save(ManifestDto manifestDto) {
        ManifestDto saved = manifestBackendRepository.save(manifestDto);
        generation.incrementAndGet();
        cached = null;
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ManifestSavedEvent(saved != null ? saved : manifestDto));
        }
        return saved;
    }

    private record CachedList(List<ManifestDto> manifests, long loadedAt) {
    }
}

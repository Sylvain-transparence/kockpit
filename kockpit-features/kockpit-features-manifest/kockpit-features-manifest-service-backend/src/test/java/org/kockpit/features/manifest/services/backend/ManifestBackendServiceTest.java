package org.kockpit.features.manifest.services.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.kockpit.features.manifest.services.ManifestBackendRepository;
import org.kockpit.features.manifest.services.dto.ManifestDto;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestBackendServiceTest {

    private ManifestBackendService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.destroy();
        }
    }

    @Test
    void list_is_served_from_the_copy_without_reading_the_repository_again() {
        CountingRepository repository = new CountingRepository();
        service = new ManifestBackendService(repository, Duration.ofMinutes(10));

        service.list();
        service.list();
        service.list();

        assertThat(repository.reads.get()).isEqualTo(1);
    }

    @Test
    void the_copy_is_reloaded_in_the_background_every_refresh_period() {
        CountingRepository repository = new CountingRepository();
        service = new ManifestBackendService(repository, Duration.ofMillis(50));
        service.afterPropertiesSet();

        awaitUntil(() -> repository.reads.get() >= 3);
        int reads = repository.reads.get();
        service.list();

        assertThat(repository.reads.get()).isEqualTo(reads);
    }

    @Test
    void save_drops_the_copy() {
        CountingRepository repository = new CountingRepository();
        service = new ManifestBackendService(repository, Duration.ofMinutes(10));
        service.list();

        service.save(manifest("added"));
        service.list();

        assertThat(repository.reads.get()).isEqualTo(2);
    }

    @Test
    void a_failed_reload_keeps_the_previous_list() {
        CountingRepository repository = new CountingRepository();
        service = new ManifestBackendService(repository, Duration.ofMillis(50));
        List<ManifestDto> before = service.list();

        repository.failing.set(true);
        service.afterPropertiesSet();
        awaitUntil(() -> repository.failures.get() >= 2);

        assertThat(service.list()).isSameAs(before);
    }

    @Test
    void the_refresh_period_must_be_positive() {
        CountingRepository repository = new CountingRepository();

        assertThatThrownBy(() -> new ManifestBackendService(repository, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManifestBackendService(repository, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.onSpinWait();
        }
    }

    private static ManifestDto manifest(String name) {
        return ManifestDto.builder().name(name).domain("platform").env("dev").build();
    }

    static class CountingRepository implements ManifestBackendRepository {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public List<ManifestDto> findAll() {
            if (failing.get()) {
                failures.incrementAndGet();
                throw new IllegalStateException("S3 unavailable");
            }
            reads.incrementAndGet();
            return List.of(manifest("m" + reads.get()));
        }

        @Override
        public Optional<ManifestDto> findByName(String name) {
            return Optional.empty();
        }

        @Override
        public ManifestDto save(ManifestDto manifestDto) {
            return manifestDto;
        }
    }
}

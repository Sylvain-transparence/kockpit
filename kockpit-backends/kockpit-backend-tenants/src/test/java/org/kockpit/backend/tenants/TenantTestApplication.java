package org.kockpit.backend.tenants;

import org.kockpit.features.manifest.services.ManifestBackendRepository;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.kockpit.features.manifest.services.backend.ManifestSavedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Security, tenants and the manifest backend (API, access control) as the backend scans them, manifests in memory. */
@SpringBootApplication(scanBasePackages = {
        "org.kockpit.backend.security",
        "org.kockpit.backend.tenants",
        "org.kockpit.features.manifest.services.backend"})
class TenantTestApplication {

    /** Refuses to store the manifests of domains starting with "broken", to test the rollback. */
    static class InMemoryManifests implements ManifestBackendRepository {

        final Map<String, ManifestDto> byName = new ConcurrentHashMap<>();

        @Override
        public List<ManifestDto> findAll() {
            return List.copyOf(byName.values());
        }

        @Override
        public Optional<ManifestDto> findByName(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public ManifestDto save(ManifestDto manifest) {
            if (manifest.getDomain().startsWith("broken")) {
                throw new IllegalStateException("storage unavailable");
            }
            byName.put(manifest.getName(), manifest);
            return manifest;
        }
    }

    /** Stands for the search API, behind the manifest access interceptor. */
    @RestController
    static class AuditsEndpoint {
        @GetMapping("/{domain}/{env}/audits/_search")
        String audits(@PathVariable String domain, @PathVariable String env) {
            return "audits of " + domain + "/" + env;
        }
    }

    /** The manifests saved, as {@link ManifestSavedEvent}s announced them (on the server's threads). */
    static class SavedManifests {

        final List<String> names = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ManifestSavedEvent event) {
            names.add(event.manifest().getName());
        }
    }

    @Bean
    SavedManifests savedManifests() {
        return new SavedManifests();
    }

    @Bean
    InMemoryManifests manifestBackendRepository() {
        InMemoryManifests manifests = new InMemoryManifests();
        manifests.byName.put("insurance-dev.json", ManifestDto.builder()
                .name("insurance-dev.json").domain("insurance").env("dev")
                .groups(List.of("insurance-team")).services(List.of()).build());
        return manifests;
    }
}

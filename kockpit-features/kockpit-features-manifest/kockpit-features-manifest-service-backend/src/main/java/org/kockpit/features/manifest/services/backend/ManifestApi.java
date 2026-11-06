package org.kockpit.features.manifest.services.backend;

import lombok.RequiredArgsConstructor;
import org.kockpit.features.manifest.services.ManifestAggregator;
import org.kockpit.features.manifest.services.dto.ManifestDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("manifests")
@RequiredArgsConstructor
public class ManifestApi {

    private final ManifestBackendService manifestBackendService;

    private final ManifestAccessService manifestAccessService;

    /** The manifests (and, within them, the services) the connected user has access to. */
    @GetMapping
    List<ManifestDto> list(Authentication authentication) {
        return manifestAccessService.visibleManifests(authentication);
    }

    /**
     * One entry per domain/env (menus / domain-env selector): the services of every manifest
     * declaring it, merged after access filtering - each manifest's groups/policies still decide
     * which of its own services the user gets.
     */
    @GetMapping("aggregated")
    List<ManifestDto> aggregated(Authentication authentication) {
        return ManifestAggregator.byDomainAndEnv(manifestAccessService.visibleManifests(authentication));
    }

    // A manifest the user may not see answers 404 like a missing one, so names aren't disclosed.
    @GetMapping("{name}")
    ResponseEntity<ManifestDto> byName(@PathVariable String name, Authentication authentication) {
        if (!ManifestNames.isValidName(name)) {
            return ResponseEntity.notFound().build();
        }
        return manifestAccessService.visibleManifest(name, authentication)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 400 when the name, domain or env could escape the storage / index naming (see
     * {@link ManifestNames}), 403 when access control denies the save (see
     * {@link ManifestAccessService#whySaveDenied}).
     */
    @PostMapping
    ResponseEntity<?> save(@RequestBody ManifestDto manifestDto, Authentication authentication) {
        if (manifestDto.getName() != null && !ManifestNames.isValidName(manifestDto.getName())) {
            return error(HttpStatus.BAD_REQUEST, "Invalid manifest name: letters, digits, '.', '_' and '-' only, starting with a letter or digit");
        }
        if (!ManifestNames.isValidSegment(manifestDto.getDomain()) || !ManifestNames.isValidSegment(manifestDto.getEnv())) {
            return error(HttpStatus.BAD_REQUEST, "Invalid domain or env: letters, digits, '.', '_' and '-' only, starting with a letter or digit");
        }
        Optional<String> denied = manifestAccessService.whySaveDenied(authentication, manifestDto);
        if (denied.isPresent()) {
            return error(HttpStatus.FORBIDDEN, denied.get());
        }
        return ResponseEntity.ok(manifestBackendService.save(manifestDto));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", status == HttpStatus.FORBIDDEN ? "forbidden" : "bad_request",
                "message", message));
    }
}

package org.kockpit.features.manifest.services.backend;

import org.kockpit.features.manifest.services.dto.ManifestDto;

/**
 * Published by {@link ManifestBackendService#save} once a manifest is stored (console upload,
 * tenant provisioning), e.g. to start polling a domain/env that just appeared.
 */
public record ManifestSavedEvent(ManifestDto manifest) {
}

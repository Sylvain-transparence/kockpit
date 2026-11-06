package org.kockpit.features.manifest.services.backend;

import java.util.regex.Pattern;

/**
 * Allowed shapes for the names that end up in storage paths and OpenSearch index names.
 *
 * <p>domain and env come from the URL ({@code /{domain}/{env}/audits}) and are used as-is to build
 * the audit alias ({@code <domain>-<index>-<env>-read}) and the communication storage paths
 * ({@code <domain>/<env>/...}): a {@code *} or {@code ,} in them would widen a search to other
 * domains' indices, a {@code ..} or {@code /} would leave the storage directory. A manifest name
 * is a file / object name in the manifest storage, same concern.
 */
public final class ManifestNames {

    // letter or digit first: no ".", "..", "-x" or "_x" (OpenSearch rejects index names starting with - or _)
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    private ManifestNames() {
    }

    /** A domain or env: letters, digits, '.', '_', '-', starting with a letter or digit. */
    public static boolean isValidSegment(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }

    /** A manifest name (file / object name): same characters, no path separator. */
    public static boolean isValidName(String value) {
        return value != null && NAME.matcher(value).matches();
    }
}

package org.kockpit.gateway;

import java.util.regex.Pattern;

/**
 * What a client may put in the fields that end up in file names (filesystem messages are stored
 * as {@code <domain>/<env>/<appId>-<id>.json}) or Kafka keys: no path separators, no "..".
 */
final class Names {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private Names() {
    }

    static boolean isValid(String value) {
        return value != null && SEGMENT.matcher(value).matches() && !value.contains("..");
    }
}

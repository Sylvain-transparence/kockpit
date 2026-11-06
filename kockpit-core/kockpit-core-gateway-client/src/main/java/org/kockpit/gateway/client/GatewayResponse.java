package org.kockpit.gateway.client;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** A gateway answer; header names in lower case. */
public record GatewayResponse(int status, byte[] body, Map<String, String> headers) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    /** Retry-After in seconds (the gateway never sends an HTTP date). */
    public Optional<Duration> retryAfter() {
        return header("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }
}

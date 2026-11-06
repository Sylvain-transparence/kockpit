package org.kockpit.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Every {@code /ingest/**} call needs an active API key, in {@code X-Kockpit-Api-Key} (or
 * {@code Authorization: Bearer kpk_...}); the key is then a request attribute for {@link IngestApi}.
 * A client IP sending too many unknown keys gets 429s for a minute (brute force, and the database).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Kockpit-Api-Key";

    public static final String ATTRIBUTE = "kockpit.gateway.apiKey";

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final ApiKeyStore keys;

    private final RateLimiter limiter;

    private final int failuresPerIpPerMinute;

    public ApiKeyFilter(ApiKeyStore keys, RateLimiter limiter, GatewayProperties properties) {
        this.keys = keys;
        this.limiter = limiter;
        this.failuresPerIpPerMinute = properties.getKeys().getFailuresPerIpPerMinute();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/ingest/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ipKey = "key-failures:" + request.getRemoteAddr();
        if (limiter.isBlocked(ipKey, failuresPerIpPerMinute, MINUTE)) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
            write(response, 429, "too_many_requests", "Too many requests with an invalid API key, retry in a minute");
            return;
        }
        String presented = presented(request);
        if (presented == null) {
            write(response, 401, "missing_api_key", "Send your API key in the " + HEADER + " header");
            return;
        }
        Optional<ApiKeyStore.ApiKey> key = keys.find(presented);
        if (key.isEmpty()) {
            limiter.tryAcquire(ipKey, Integer.MAX_VALUE, MINUTE);
            write(response, 401, "invalid_api_key", "Unknown or revoked API key");
            return;
        }
        request.setAttribute(ATTRIBUTE, key.get());
        chain.doFilter(request, response);
    }

    private static String presented(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }

    private static void write(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // fixed texts: nothing to escape
        response.getWriter().write("{\"error\":\"%s\",\"message\":\"%s\"}".formatted(error, message));
    }
}

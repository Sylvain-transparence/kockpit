package org.kockpit.gateway.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Calls the ingestion gateway with the tenant's API key. Thread-safe, one per application.
 * Never logs the key.
 */
public class GatewayClient {

    public static final String API_KEY_HEADER = "X-Kockpit-Api-Key";

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    private final URI baseUri;

    private final String apiKey;

    private final Duration requestTimeout;

    private final HttpClient httpClient;

    public GatewayClient(String url, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = baseUri(url);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("kockpit.gateway.api-key is required: create a key on the console's \"Tenant & API keys\" page");
        }
        this.apiKey = apiKey.trim();
        if (!this.apiKey.startsWith("kpk_")) {
            log.warn("⚠️ kockpit.gateway.api-key doesn't look like a Kockpit API key (kpk_...)");
        }
        if ("http".equals(baseUri.getScheme()) && !isLocal(baseUri.getHost())) {
            log.warn("⚠️ kockpit.gateway.url {} isn't https: the API key and the audits travel in clear", baseUri);
        }
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER) // never resend the key elsewhere
                .build();
        log.info("✅ Kockpit gateway {}", baseUri);
    }

    public URI baseUri() {
        return baseUri;
    }

    /** POST of a JSON body, gzip-compressed when {@code gzip}. */
    public GatewayResponse postJson(String path, byte[] json, boolean gzip) throws IOException, InterruptedException {
        HttpRequest.Builder request = request(path, Map.of())
                .header("Content-Type", "application/json");
        if (gzip) {
            request.header("Content-Encoding", "gzip");
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofByteArray(gzip ? gzip(json) : json)).build());
    }

    /** GET with query parameters (null values left out). */
    public GatewayResponse get(String path, Map<String, String> query) throws IOException, InterruptedException {
        return send(request(path, query).GET().build());
    }

    private HttpRequest.Builder request(String path, Map<String, String> query) {
        String queryString = query.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(baseUri + path + (queryString.isEmpty() ? "" : "?" + queryString));
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header(API_KEY_HEADER, apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "kockpit-sdk");
    }

    private GatewayResponse send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });
        return new GatewayResponse(response.statusCode(), response.body(), headers);
    }

    static URI baseUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("kockpit.gateway.url is required, e.g. https://ingest.example.com");
        }
        URI uri;
        try {
            uri = URI.create(url.trim().replaceAll("/+$", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("kockpit.gateway.url %s isn't a valid URL".formatted(url), e);
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalStateException("kockpit.gateway.url %s must be an http(s) URL, e.g. https://ingest.example.com".formatted(url));
        }
        if (uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalStateException("kockpit.gateway.url %s must not hold credentials, a query or a fragment".formatted(url));
        }
        return uri;
    }

    private static boolean isLocal(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || host.endsWith(".localhost");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static boolean isGzip(byte[] data) {
        return data != null && data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    public static byte[] gzip(byte[] data) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, data.length / 4))) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(data);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Uncompressed, at most {@code maxBytes + 1} bytes (more means too large). */
    public static byte[] gunzip(byte[] data, int maxBytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readNBytes(maxBytes + 1);
        }
    }

    /** The gateway's {@code {"error": ..., "message": ...}} message, else the start of the body. */
    public static Optional<String> errorMessage(GatewayResponse response) {
        if (response.body() == null || response.body().length == 0) {
            return Optional.empty();
        }
        String body = new String(response.body(), StandardCharsets.UTF_8);
        int key = body.indexOf("\"message\"");
        if (key >= 0) {
            int start = body.indexOf('"', body.indexOf(':', key) + 1);
            int end = start < 0 ? -1 : body.indexOf('"', start + 1);
            if (start >= 0 && end > start) {
                return Optional.of(body.substring(start + 1, end));
            }
        }
        return Optional.of(body.length() > 200 ? body.substring(0, 200) + "…" : body);
    }
}

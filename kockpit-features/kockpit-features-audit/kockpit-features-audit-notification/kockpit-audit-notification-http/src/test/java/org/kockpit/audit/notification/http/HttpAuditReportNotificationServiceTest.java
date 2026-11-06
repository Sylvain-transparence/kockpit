package org.kockpit.audit.notification.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kockpit.audit.api.AuditReportWrapper;
import org.kockpit.gateway.client.GatewayClient;
import org.kockpit.gateway.client.SendGate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HttpAuditReportNotificationServiceTest {

    private HttpServer server;

    /** Uncompressed request bodies, in order. */
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    /** Statuses to answer, in order; 202 once empty. */
    private final Deque<Integer> statuses = new ArrayDeque<>();

    private GatewayClient client;

    private final HttpAuditProperties properties = new HttpAuditProperties();

    private SendGate gate;

    private HttpAuditReportNotificationService service;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest/v1/audits", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            assertThat(exchange.getRequestHeaders().getFirst("Content-Encoding")).isEqualTo("gzip");
            assertThat(exchange.getRequestHeaders().getFirst(GatewayClient.API_KEY_HEADER)).isEqualTo("kpk_test");
            bodies.add(new String(GatewayClient.gunzip(raw, 10_000_000), StandardCharsets.UTF_8));
            Integer status;
            synchronized (statuses) {
                status = statuses.isEmpty() ? 202 : statuses.poll();
            }
            String answer = switch (status) {
                case 429 -> "{\"error\":\"quota_exceeded\",\"message\":\"The daily quota of 10 audits is used up\"}";
                case 400 -> "{\"error\":\"bad_request\",\"message\":\"env local isn't one of the tenant's envs [dev]\"}";
                default -> "{\"accepted\":1}";
            };
            if (status == 429) {
                exchange.getResponseHeaders().add("Retry-After", "3600");
            }
            byte[] response = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), "kpk_test",
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        properties.setRetryBackoff(Duration.ofMillis(10));
        gate = new SendGate("audits");
        service = new HttpAuditReportNotificationService(client, properties, gate, Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void audits_compressed_one_by_one_are_sent_as_one_gzip_array() {
        service.notify(List.of(audit("a1", true), audit("a2", false), audit("a3", true)));

        assertThat(bodies).containsExactly("[" + json("a1") + "," + json("a2") + "," + json("a3") + "]");
    }

    @Test
    void batches_stay_within_the_gateways_limits() {
        properties.setMaxBatch(2);
        service.notify(IntStream.range(0, 5).mapToObj(i -> audit("c" + i, true)).toList());
        assertThat(bodies).hasSize(3);

        bodies.clear();
        properties.setMaxBatch(500);
        properties.setMaxBodyBytes(json("s0").length() * 2 + 3); // [x,y]
        service.notify(IntStream.range(0, 5).mapToObj(i -> audit("s" + i, false)).toList());
        assertThat(bodies).hasSize(3);
        assertThat(bodies.getFirst()).isEqualTo("[" + json("s0") + "," + json("s1") + "]");
    }

    @Test
    void an_audit_larger_than_the_gateway_accepts_is_dropped_alone() {
        properties.setMaxBodyBytes(200);
        AuditReportWrapper huge = new AuditReportWrapper("d", "dev", "shop", null, "big",
                GatewayClient.gzip(("{\"id\":\"big\",\"x\":\"" + "y".repeat(500) + "\"}").getBytes()));

        service.notify(List.of(audit("ok", true), huge));

        assertThat(bodies).containsExactly("[" + json("ok") + "]");
    }

    @Test
    void over_quota_sending_stops_until_the_quota_resets() {
        properties.setMaxBatch(1);
        statuses.add(202);
        statuses.add(429);

        service.notify(List.of(audit("q1", true), audit("q2", true), audit("q3", true)));
        service.notify(List.of(audit("q4", true)));

        assertThat(bodies).hasSize(2); // q1 accepted, q2 refused, q3 and q4 never sent
        assertThat(gate.isOpen()).isFalse();
        assertThat(gate.dropped()).isEqualTo(3);
    }

    @Test
    void refused_audits_are_dropped_and_the_next_batches_still_sent() {
        properties.setMaxBatch(1);
        statuses.add(400);

        service.notify(List.of(audit("r1", true), audit("r2", true)));

        assertThat(bodies).hasSize(2);
        assertThat(gate.isOpen()).isTrue();
    }

    @Test
    void an_unavailable_gateway_is_retried_then_paused() {
        statuses.add(503);
        service.notify(List.of(audit("u1", true)));
        assertThat(bodies).hasSize(2); // one retry was enough

        bodies.clear();
        statuses.addAll(List.of(503, 502, 503));
        service.notify(List.of(audit("u2", true)));
        assertThat(bodies).hasSize(3); // first try + 2 retries
        assertThat(gate.isOpen()).isFalse();

        service.notify(List.of(audit("u3", true)));
        assertThat(bodies).hasSize(3);
    }

    private static String json(String id) {
        return "{\"id\":\"%s\",\"env\":\"dev\",\"appId\":\"shop\",\"type\":\"http\"}".formatted(id);
    }

    private static AuditReportWrapper audit(String id, boolean compressed) {
        byte[] data = json(id).getBytes(StandardCharsets.UTF_8);
        return new AuditReportWrapper("kockpit", "dev", "shop", null, id, compressed ? GatewayClient.gzip(data) : data);
    }
}

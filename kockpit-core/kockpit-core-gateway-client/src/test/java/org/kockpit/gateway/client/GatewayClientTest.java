package org.kockpit.gateway.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayClientTest {

    @Test
    void the_url_and_key_are_checked_at_startup() {
        assertThat(GatewayClient.baseUri("https://ingest.example.com//").toString()).isEqualTo("https://ingest.example.com");
        assertThat(GatewayClient.baseUri(" http://localhost:8080 ").toString()).isEqualTo("http://localhost:8080");
        for (String bad : new String[]{null, " ", "ingest.example.com", "ftp://ingest.example.com",
                "https://user:pw@ingest.example.com", "https://ingest.example.com?x=1"}) {
            assertThatThrownBy(() -> GatewayClient.baseUri(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("kockpit.gateway.url");
        }
        assertThatThrownBy(() -> new GatewayClient("https://ingest.example.com", " ", Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("kockpit.gateway.api-key");
    }

    @Test
    void gzip_round_trip_and_size_bound() throws Exception {
        byte[] json = "{\"a\":\"%s\"}".formatted("x".repeat(5000)).getBytes(StandardCharsets.UTF_8);
        byte[] gzip = GatewayClient.gzip(json);
        assertThat(GatewayClient.isGzip(gzip)).isTrue();
        assertThat(GatewayClient.isGzip(json)).isFalse();
        assertThat(GatewayClient.gunzip(gzip, 10_000)).isEqualTo(json);
        assertThat(GatewayClient.gunzip(gzip, 100)).hasSize(101); // more than the bound: too large
    }

    @Test
    void the_gateways_error_message_is_read() {
        GatewayResponse response = new GatewayResponse(429,
                "{\"error\":\"quota_exceeded\",\"message\":\"A batch of 3 audits exceeds the 2 left today\"}".getBytes(), Map.of("retry-after", "3600"));
        assertThat(GatewayClient.errorMessage(response)).contains("A batch of 3 audits exceeds the 2 left today");
        assertThat(response.retryAfter()).contains(Duration.ofHours(1));
        assertThat(response.header("Retry-After")).contains("3600");
        assertThat(GatewayClient.errorMessage(new GatewayResponse(502, "bad gateway".getBytes(), Map.of()))).contains("bad gateway");
    }

    @Test
    void the_gate_drops_while_closed_then_opens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-06T10:00:00Z"));
        SendGate gate = new SendGate("audits", clock);
        assertThat(gate.isOpen()).isTrue();

        gate.close(Duration.ofMinutes(10), "quota");
        assertThat(gate.isOpen()).isFalse();
        gate.drop(3);
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        assertThat(gate.isOpen()).isFalse();
        assertThat(gate.dropped()).isEqualTo(3);

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(gate.isOpen()).isTrue();
        assertThat(gate.dropped()).isZero();
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

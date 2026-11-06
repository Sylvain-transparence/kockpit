package org.kockpit.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code kockpit.gateway.*} */
@Data
@ConfigurationProperties("kockpit.gateway")
public class GatewayProperties {

    private Audits audits = new Audits();

    private Messages messages = new Messages();

    private Keys keys = new Keys();

    @Data
    public static class Audits {
        private String topic = "audits";
        /** Retention forced on every audit: it is part of the OpenSearch index name and policy. */
        private int ttlDays = 7;
        /** Audits per request. */
        private int maxBatch = 500;
        /** Request body, after gzip decompression. */
        private int maxBodyBytes = 1024 * 1024;
        /** How long a request waits for Kafka to acknowledge its audits. */
        private Duration sendTimeout = Duration.ofSeconds(10);
    }

    @Data
    public static class Messages {
        private int maxBodyBytes = 64 * 1024;
        /** Messages one key may publish per hour (heartbeats, acknowledgements). */
        private int perKeyPerHour = 1200;
        /** Messages returned by one poll at most, newest first. */
        private int maxPoll = 200;
    }

    @Data
    public static class Keys {
        /** A revoked key, or a changed quota, applies within this delay. */
        private Duration cacheTtl = Duration.ofSeconds(30);
        /** Requests with an unknown key one client IP may make per minute before getting 429s. */
        private int failuresPerIpPerMinute = 30;
    }
}

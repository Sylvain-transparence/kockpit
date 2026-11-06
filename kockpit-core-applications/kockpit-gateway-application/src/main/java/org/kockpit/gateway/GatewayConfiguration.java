package org.kockpit.gateway;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class GatewayConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RateLimiter rateLimiter(Clock clock) {
        return new RateLimiter(clock);
    }

    @Bean
    ProducerFactory<String, byte[]> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.getProducer().getProperties());
        var producerServers = kafkaProperties.getProducer().getBootstrapServers();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                producerServers != null && !producerServers.isEmpty() ? producerServers : kafkaProperties.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        // a request answers 503 rather than hanging when Kafka is unreachable
        config.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        config.putIfAbsent(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 9_000);
        config.putIfAbsent(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 8_000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, byte[]> kafkaTemplate(ProducerFactory<String, byte[]> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    AuditSink auditSink(KafkaTemplate<String, byte[]> kafkaTemplate, GatewayProperties properties) {
        return new KafkaAuditSink(kafkaTemplate, properties.getAudits().getTopic(), properties.getAudits().getSendTimeout());
    }
}

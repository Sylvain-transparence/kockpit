package org.kockpit.gateway;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class KafkaAuditSink implements AuditSink {

    private final KafkaTemplate<String, byte[]> kafka;

    private final String topic;

    private final Duration timeout;

    public KafkaAuditSink(KafkaTemplate<String, byte[]> kafka, String topic, Duration timeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void send(List<Record> records) throws Exception {
        List<CompletableFuture<SendResult<String, byte[]>>> sent = records.stream()
                .map(record -> kafka.send(topic, record.key(), record.value()))
                .toList();
        CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}

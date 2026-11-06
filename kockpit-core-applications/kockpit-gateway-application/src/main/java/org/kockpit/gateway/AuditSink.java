package org.kockpit.gateway;

import java.util.List;

/** Where accepted audits go: the audit stream's Kafka topic ({@link KafkaAuditSink}). */
public interface AuditSink {

    /** A Kafka record: key "domain-env-appId" (as the SDK's Kafka notifier), the audit's JSON. */
    record Record(String key, byte[] value) {
    }

    /** Returns once every record is acknowledged; throws if any couldn't be. */
    void send(List<Record> records) throws Exception;
}

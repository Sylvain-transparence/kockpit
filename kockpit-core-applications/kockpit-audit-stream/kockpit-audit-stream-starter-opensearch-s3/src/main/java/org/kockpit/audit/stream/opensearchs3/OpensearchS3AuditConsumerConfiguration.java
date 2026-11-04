package org.kockpit.audit.stream.opensearchs3;

import org.kockpit.audit.stream.api.AuditConsumer;
import org.kockpit.audit.stream.api.AuditStreamJson;
import org.kockpit.audit.stream.opensearch.OpensearchIndexer;
import org.kockpit.audit.stream.s3.S3AuditConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import static org.kockpit.audit.stream.api.AuditStreamJson.mapper;

@AutoConfiguration
public class OpensearchS3AuditConsumerConfiguration {

    // kockpit.audit.stream.consumer=opensearch-s3 selects this composite consumer instead of the
    // standalone "s3"/"opensearch" facades (which are gated the same way in their own starters) -
    // KockpitStreamApplication dispatches every event to every AuditConsumer bean in the context,
    // so exactly one of the three must be active at a time.
    //
    // s3AuditConsumer MUST be injected as the bean S3AuditConsumerConfiguration already registers
    // (not built with `new` here) - S3AuditConsumer.flush() relies on @Scheduled, which only fires
    // for Spring-managed beans; a plain `new S3AuditConsumer(...)` never goes through the
    // container's post-processing, so its scheduler would silently never run.
    @Bean
    @ConditionalOnBean(OpensearchIndexer.class)
    @ConditionalOnProperty(name = "kockpit.audit.stream.consumer", havingValue = "opensearch-s3")
    public AuditConsumer opensearchS3AuditConsumer(
            S3AuditConsumer s3AuditConsumer,
            OpensearchIndexer opensearchIndexer,
            @Value("${kockpit.audit.stream.ttl_default_in_days:1}") int ttlDefaultInDays
    ) {
        return new OpensearchS3AuditConsumer(s3AuditConsumer, opensearchIndexer, mapper(), ttlDefaultInDays);
    }
}

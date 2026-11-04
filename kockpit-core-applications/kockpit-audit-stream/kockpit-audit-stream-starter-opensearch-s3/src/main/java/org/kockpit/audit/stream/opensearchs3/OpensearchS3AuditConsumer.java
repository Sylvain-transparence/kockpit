package org.kockpit.audit.stream.opensearchs3;

import lombok.RequiredArgsConstructor;
import org.kockpit.audit.stream.api.AuditConsumer;
import org.kockpit.audit.stream.api.AuditStreamJson;
import org.kockpit.audit.stream.api.model.AuditReport;
import org.kockpit.audit.stream.opensearch.IndexMetadata;
import org.kockpit.audit.stream.opensearch.OpensearchIndexer;
import org.kockpit.audit.stream.s3.S3AuditConsumer;
import org.kockpit.audit.stream.s3.S3Key;
import org.kockpit.audit.stream.s3.S3WriteEvent;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class OpensearchS3AuditConsumer implements AuditConsumer {

    private final S3AuditConsumer s3AuditConsumer;

    private final OpensearchIndexer opensearchIndexer;

    private final ObjectMapper objectMapper;

    // Same value S3AuditConsumer falls back on (kockpit.audit.stream.ttl_default_in_days), so a
    // report without a ttl gets the same retention in OpenSearch as the S3 object it points to.
    private final int ttlDefaultInDays;

    @Override
    public void accept(List<byte[]> bytes) {
        // Some producers already offload the full report (audits included) to their own S3
        // object before publishing to Kinesis, and set s3Key on the wire record accordingly (see
        // S3Key.existingS3Key). Those must be indexed as-is, preserving that pointer - not routed
        // through s3AuditConsumer, which would archive a strictly-worse (root-only) copy and
        // overwrite it. Only records without one go through the normal archive-then-index path.
        Map<Boolean, List<byte[]>> partitioned = bytes.stream()
                .collect(Collectors.partitioningBy(record -> S3Key.read(record).getExistingS3Key() != null));

        List<byte[]> alreadyOffloaded = partitioned.get(true);
        if (!alreadyOffloaded.isEmpty()) {
            indexAlreadyOffloaded(alreadyOffloaded);
        }

        List<byte[]> needsArchiving = partitioned.get(false);
        if (!needsArchiving.isEmpty()) {
            s3AuditConsumer.accept(needsArchiving);
        }
    }

    // Already-offloaded records are indexed synchronously in accept(); only the archive path
    // buffers.
    @Override
    public void drain() {
        s3AuditConsumer.drain();
    }

    private void indexAlreadyOffloaded(List<byte[]> bytes) {
        // The producer's own S3 object: its retention is not ours to know, only fill in a missing ttl.
        bytes.stream().map(this::parseAuditReport)
                .peek(auditReport -> auditReport.setTtl(resolveTtl(auditReport, Integer.MAX_VALUE)))
                .collect(Collectors.groupingBy(IndexMetadata::of))
                .forEach((indexMetadata, auditReports) -> opensearchIndexer.index(auditReports, indexMetadata));
    }

    @EventListener
    public void onS3Write(S3WriteEvent s3WriteEvent) {
        s3WriteEvent.getBatch().stream().map(s3Record -> {
            AuditReport auditReport = parseAuditReport(s3Record.getData());

            auditReport.setS3Key(s3WriteEvent.getS3Key());
            auditReport.setS3Uri("s3://%s/%s".formatted(s3WriteEvent.getS3BucketName(), s3WriteEvent.getS3Key()));
            auditReport.setS3Size(s3Record.getLength());
            auditReport.setS3Offset(s3Record.getOffset());

            auditReport.setAudits(List.of());
            // S3 rounds the object's retention up to an allowed value but caps it at the longest
            // one, so a ttl above that cap would keep the index (and its pointer) longer than the
            // object it points to.
            auditReport.setTtl(resolveTtl(auditReport, s3WriteEvent.getRetentionDays()));

            return auditReport;
        }).collect(Collectors.groupingBy(IndexMetadata::of))
                .forEach((indexMetadata, auditReports) -> opensearchIndexer.index(auditReports, indexMetadata));
    }

    // Without a ttl, IndexMetadata has none and building the index name fails.
    private int resolveTtl(AuditReport auditReport, int maxDays) {
        Integer ttl = auditReport.getTtl();
        return Math.min(ttl != null ? ttl : ttlDefaultInDays, maxDays);
    }

    private AuditReport parseAuditReport(byte[] data) {
        // data is the archived wire-format bytes, possibly gzip-compressed (see AuditStreamJson)
        // - decompress before parsing, and readValue (not convertValue) to actually parse it as
        // JSON rather than round-tripping the raw bytes through Jackson's default
        // byte[]-as-base64-string handling.
        try {
            AuditReport auditReport = objectMapper.readValue(AuditStreamJson.read(data), AuditReport.class);
            // Producers that don't populate audits (the vast majority - see S3Key.existingS3Key)
            // leave it null; normalize to empty rather than indexing a null field.
            if (auditReport.getAudits() == null) {
                auditReport.setAudits(List.of());
            }
            return auditReport;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

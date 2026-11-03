package org.kockpit.audit.stream.opensearchs3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kockpit.audit.stream.api.AuditStreamJson;
import org.kockpit.audit.stream.api.model.Audit;
import org.kockpit.audit.stream.api.model.AuditReport;
import org.kockpit.audit.stream.opensearch.IndexMetadata;
import org.kockpit.audit.stream.opensearch.OpensearchIndexer;
import org.kockpit.audit.stream.s3.S3AuditConsumer;
import org.kockpit.audit.stream.s3.S3Key;
import org.kockpit.audit.stream.s3.S3Record;
import org.kockpit.audit.stream.s3.S3WriteEvent;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * OpensearchS3AuditConsumer routes each record to exactly one of two paths, based on whether the
 * producer already offloaded the full report to its own S3 object (AuditReport.s3Key set): straight
 * to OpenSearch if so, through S3AuditConsumer (archive, then index on S3WriteEvent) otherwise.
 * Getting this wrong either double-archives a record S3AuditConsumer would then silently overwrite
 * with a strictly-worse copy, or never indexes one that skipped S3AuditConsumer entirely.
 */
class OpensearchS3AuditConsumerTest {

    private final ObjectMapper mapper = AuditStreamJson.mapper();

    private final S3AuditConsumer s3AuditConsumer = mock(S3AuditConsumer.class);
    private final OpensearchIndexer opensearchIndexer = mock(OpensearchIndexer.class);

    private final OpensearchS3AuditConsumer consumer =
            new OpensearchS3AuditConsumer(s3AuditConsumer, opensearchIndexer, mapper);

    @Test
    @DisplayName("Un audit deja offload (s3Uri/s3Key deja renseignes) est indexe directement, sans passer par S3AuditConsumer")
    void indexes_already_offloaded_report_directly() {
        AuditReport report = new AuditReport();
        report.setId("evt-1");
        report.setDomain("kockpit");
        report.setEnv("prod");
        report.setTtl(30);
        report.setS3Uri("s3://audits-prod/sample/2026/09/03/existing.batch");
        report.setS3Key("sample/2026/09/03/existing.batch");
        Audit audit = new Audit();
        audit.setType("builtin.web");
        report.setAudits(List.of(audit));

        consumer.accept(List.of(mapper.writeValueAsBytes(report)));

        verifyNoInteractions(s3AuditConsumer);

        ArgumentCaptor<List<AuditReport>> reports = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<IndexMetadata> metadata = ArgumentCaptor.forClass(IndexMetadata.class);
        verify(opensearchIndexer).index(reports.capture(), metadata.capture());

        assertThat(reports.getValue()).hasSize(1);
        AuditReport indexed = reports.getValue().get(0);
        assertThat(indexed.getId()).isEqualTo("evt-1");
        // Not overwritten with a strictly-worse (root-only) archive pointer - the report's own
        // s3Uri/s3Key from the wire record must reach OpenSearch untouched.
        assertThat(indexed.getS3Uri()).isEqualTo("s3://audits-prod/sample/2026/09/03/existing.batch");
        assertThat(indexed.getS3Key()).isEqualTo("sample/2026/09/03/existing.batch");
        // Unlike onS3Write's archive path, this one indexes the report exactly as the producer
        // sent it - audits included, not stripped.
        assertThat(indexed.getAudits()).extracting(Audit::getType).containsExactly("builtin.web");

        assertThat(metadata.getValue().getDomain()).isEqualTo("kockpit");
        assertThat(metadata.getValue().getEnv()).isEqualTo("prod");
        assertThat(metadata.getValue().getTtl()).isEqualTo(30);
    }

    @Test
    @DisplayName("Un audit sans s3Uri/s3Key passe par S3AuditConsumer pour archivage, sans indexation directe")
    void archives_report_without_existing_s3_pointer_via_s3_consumer() {
        AuditReport report = new AuditReport();
        report.setId("evt-2");
        report.setDomain("kockpit");
        report.setEnv("prod");
        report.setTtl(30);
        // s3Uri / s3Key volontairement absents : ce record n'a pas encore ete offload par le
        // producteur, donc il doit encore passer par S3AuditConsumer.

        byte[] bytes = mapper.writeValueAsBytes(report);
        consumer.accept(List.of(bytes));

        verifyNoInteractions(opensearchIndexer);

        ArgumentCaptor<List<byte[]>> archived = ArgumentCaptor.forClass(List.class);
        verify(s3AuditConsumer).accept(archived.capture());
        assertThat(archived.getValue()).containsExactly(bytes);
    }

    @Test
    @DisplayName("Dans un meme lot, chaque record est route independamment des autres")
    void routes_each_record_independently_within_a_batch() {
        AuditReport offloaded = new AuditReport();
        offloaded.setId("evt-offloaded");
        offloaded.setDomain("kockpit");
        offloaded.setEnv("prod");
        offloaded.setTtl(30);
        offloaded.setS3Uri("s3://audits-prod/sample/existing.batch");
        offloaded.setS3Key("sample/existing.batch");

        AuditReport notOffloaded = new AuditReport();
        notOffloaded.setId("evt-not-offloaded");
        notOffloaded.setDomain("kockpit");
        notOffloaded.setEnv("prod");
        notOffloaded.setTtl(30);

        byte[] offloadedBytes = mapper.writeValueAsBytes(offloaded);
        byte[] notOffloadedBytes = mapper.writeValueAsBytes(notOffloaded);

        consumer.accept(List.of(offloadedBytes, notOffloadedBytes));

        ArgumentCaptor<List<byte[]>> archived = ArgumentCaptor.forClass(List.class);
        verify(s3AuditConsumer).accept(archived.capture());
        assertThat(archived.getValue()).containsExactly(notOffloadedBytes);

        ArgumentCaptor<List<AuditReport>> indexed = ArgumentCaptor.forClass(List.class);
        verify(opensearchIndexer).index(indexed.capture(), any());
        assertThat(indexed.getValue()).extracting(AuditReport::getId).containsExactly("evt-offloaded");
    }

    @Test
    @DisplayName("Apres archivage par S3AuditConsumer, onS3Write indexe le rapport sans ses audits (deja en surete dans le batch S3)")
    void strips_audits_before_indexing_the_archived_copy() {
        Audit audit = new Audit();
        audit.setType("builtin.web");

        AuditReport report = new AuditReport();
        report.setId("evt-archived");
        report.setDomain("kockpit");
        report.setEnv("prod");
        report.setTtl(30);
        report.setAudits(List.of(audit));

        byte[] bytes = mapper.writeValueAsBytes(report);
        S3Record s3Record = new S3Record(bytes, new S3Key());
        s3Record.setOffset(174443);
        s3Record.setLength(2500);

        consumer.onS3Write(new S3WriteEvent(this, "d=sample/e=prod/a=app/batch.batch", List.of(s3Record), "audits-prod"));

        ArgumentCaptor<List<AuditReport>> reports = ArgumentCaptor.forClass(List.class);
        verify(opensearchIndexer).index(reports.capture(), any());

        AuditReport indexed = reports.getValue().get(0);
        assertThat(indexed.getId()).isEqualTo("evt-archived");
        // The full record - audits included - was just archived to S3 as-is; re-indexing them
        // into OpenSearch too would only duplicate what's already durably reachable via
        // s3Uri/s3Key/s3Offset/s3Size.
        assertThat(indexed.getAudits()).isEmpty();
        assertThat(indexed.getS3Uri()).isEqualTo("s3://audits-prod/d=sample/e=prod/a=app/batch.batch");
        assertThat(indexed.getS3Offset()).isEqualTo(174443);
        assertThat(indexed.getS3Size()).isEqualTo(2500);
    }
}

package org.kockpit.audit.stream.opensearch;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.audit.stream.api.model.AuditReport;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.isNull;
import static org.kockpit.audit.stream.opensearch.OpensearchHelper.*;

@Slf4j
@RequiredArgsConstructor
public class OpensearchIndexer {

    private final OpenSearchClient openSearchClient;

    private final OpensearchV3IndexManager opensearchV3IndexManager;

    private final ObjectMapper objectMapper;

    private final String indexSuffix;

    // Two supported document shapes for indexedKeyValues coexist: root-level (default, matches
    // audit_index_template.json) and nested under indexedExtensions (legacy shape, matches
    // audit_index_template_s3.json / older indexed documents). Whichever is chosen MUST match the
    // index template actually in use, or indexedKeyValues silently falls back to dynamic mapping
    // instead of the declared nested type.
    private final boolean wrapIndexedKeyValues;

    public void index(List<AuditReport> auditReports, IndexMetadata indexMetadata) {

        String indexPrefix = getIndexPrefix(indexMetadata.getDomain(), indexSuffix, indexMetadata.getEnv(), indexMetadata.getTtl());
        String aliasWrite = getAliasWrite(indexMetadata.getDomain(), indexSuffix, indexMetadata.getEnv(), indexMetadata.getTtl());
        String aliasRead = getAliasRead(indexMetadata.getDomain(), indexSuffix, indexMetadata.getEnv());

        String indexName = "<" + indexPrefix + "-{now/d}-00001>";
        // ensure the index exists (template, policy and aliases)
        opensearchV3IndexManager.ensureIndexExists(indexName, aliasWrite, aliasRead, indexPrefix, indexMetadata.getTtl());

        // require_alias: if the write alias is ever missing (e.g. mid daily switch), the bulk must
        // fail rather than let OpenSearch auto-create a concrete index under the alias name - that
        // index then "squats" the name, every later alias attach fails, and it silently receives
        // all writes for the prefix (seen on several -write aliases in production). Items failing this
        // way are logged and dropped like any bulk error (the reports stay archived in S3), and
        // the next cycle's ensureIndexExists() re-attaches the alias.
        BulkRequest bulkRequest = BulkRequest.of(b -> b.requireAlias(true).operations(
                auditReports.stream()
                        .map(auditIndexRequest -> toBulkOperation(auditIndexRequest, aliasWrite))
                        .toList())
        );
        try {
            BulkResponse bulkResponse = bulkRequest(bulkRequest);
            if (bulkResponse.errors()) {
                bulkResponse.items().stream()
                        .filter(bulkResponseItem -> bulkResponseItem.error() != null)
                        .forEach(bulkResponseItem -> {
                            //log.warn("Failed to index audit reports for index {}, error {}", aliasWrite, bulkResponseItem.error().causedBy());
                            log.warn("Failed to index audit reports for index {}, error {}", aliasWrite, bulkResponseItem.error());
                        });
            }
        } catch (Exception e) {
            log.warn("Failed to index audit reports for index {}", aliasWrite, e);
        }
    }

    @SneakyThrows
    private BulkResponse bulkRequest(BulkRequest bulkRequest) {
        BulkResponse bulkResponse = openSearchClient.bulk(bulkRequest);
        if (bulkResponse.errors()) {
            bulkResponse.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(item -> log.error("Bulk item in error {}", item.error().reason()));
        }
        return bulkResponse;
    }

    @SneakyThrows
    private BulkOperation toBulkOperation(AuditReport auditReport, String writeAlias) {
        String documentId = isNull(auditReport.getId()) ? auditReport.getRequestId() : auditReport.getId();
        JsonNode jsonNode = objectMapper.valueToTree(auditReport);
        if (jsonNode instanceof ObjectNode objectNode) {
            // The index template maps @timestamp as date/epoch_millis specifically (unlike
            // start/end, which accept the default date format) - AuditReport has no @timestamp
            // field of its own, so it's never populated by valueToTree() and must be added here,
            // as a Long, not the Instant's default ISO-8601 serialization. Set to indexing time
            // (not start/end, the report's own event time) so it reflects when this consumer
            // actually wrote the document.
            objectNode.put("@timestamp", Instant.now().toEpochMilli());
            if (wrapIndexedKeyValues) {
                wrapIndexedKeyValues(objectNode);
            }
        }

        return BulkOperation.of(op -> op
                .index(IndexOperation.of(idx -> idx
                        .index(writeAlias)
                        .id(documentId)
                        .document(jsonNode)
                ))
        );
    }

    // AuditReport itself keeps indexedKeyValues at its root (that's the real, current model shape
    // - see AuditReport.java); this only reshapes the JSON tree sent to OpenSearch, so it never
    // touches the wire/S3-archive format.
    //
    // Legacy documents have indexedExtensions as an ARRAY of objects (each holding its own
    // indexedKeyValues), not a single object - e.g. "indexedExtensions":[{"indexedKeyValues":[...]}].
    private static void wrapIndexedKeyValues(ObjectNode document) {
        JsonNode indexedKeyValues = document.remove("indexedKeyValues");
        if (indexedKeyValues != null) {
            document.putArray("indexedExtensions").addObject().set("indexedKeyValues", indexedKeyValues);
        }
    }

}

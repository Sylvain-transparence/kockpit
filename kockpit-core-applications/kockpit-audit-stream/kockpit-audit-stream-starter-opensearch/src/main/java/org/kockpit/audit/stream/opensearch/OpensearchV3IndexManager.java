package org.kockpit.audit.stream.opensearch;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.UpdateAliasesRequest;
import org.opensearch.client.opensearch.indices.update_aliases.Action;
import org.opensearch.client.opensearch.indices.update_aliases.AddAction;
import org.opensearch.client.opensearch.indices.update_aliases.RemoveAction;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

@RequiredArgsConstructor
@Slf4j
public class OpensearchV3IndexManager {

    private final OpenSearchClient client;

    private final boolean strictMode;

    private final String indexTemplateResource;

    private final String policyResource;

    @SneakyThrows
    public void ensureIndexExists(String indexName, String aliasWrite, String aliasRead, String indexPrefix, Integer ttl) {
        // create policy
        String policyId = "audit_ism_policy_" + indexPrefix;
        createISMPolicy(ttl, policyId, indexPrefix);
        // create template
        createIndexTemplate(indexPrefix, policyId, aliasWrite);
        // create index
        boolean justCreated = false;
        if (!client.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value()) {
            log.info("Creating index {}", indexName);
            justCreated = createIndexIdempotent(indexName);
            if (justCreated) {
                // Attach read alias (all logs-* indices)
                attachReadAlias(indexName, aliasRead);
            }
        }

        // Checked (and retried if wrong) on every call, not only when the index was just created:
        // if attachWriteAlias() ever fails after the index already exists, the old code's
        // if-not-exists guard meant it would NEVER be retried for that index, and the next bulk
        // write would target an alias that doesn't exist anywhere - which OpenSearch silently
        // "fixes" via dynamic index auto-creation, permanently poisoning the alias name with a
        // real, unmanaged index (see attachWriteAlias()). Re-checking here means a transient
        // failure gets retried on the next indexing cycle instead of becoming permanent.
        if (justCreated || !isWriteAliasCorrect(indexName, aliasWrite)) {
            attachWriteAlias(indexName, aliasWrite, indexPrefix);
        }
    }

    // At the daily rollover, several threads/shard-processors/tasks can all observe the index
    // missing and race to create it - the loser gets resource_already_exists_exception. That's
    // not a real failure (the index is there either way), so it must not drop the whole flush
    // batch: this used to propagate uncaught out of ensureIndexExists(), through
    // OpensearchIndexer.index() (called outside its own try/catch), and back up to whichever
    // caller happened to be catching something unrelated (see S3AuditConsumer.write()'s
    // misleading "Failed to write N audit reports to s3://..." for OpenSearch errors that have
    // nothing to do with S3), or, for producer-pre-offloaded records via
    // OpensearchS3AuditConsumer.indexAlreadyOffloaded(), fully uncaught.
    @SneakyThrows
    private boolean createIndexIdempotent(String indexName) {
        try {
            client.indices().create(CreateIndexRequest.of(c -> c.index(indexName)));
            return true;
        } catch (OpenSearchException e) {
            if (!"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
            log.info("Index {} already exists (created concurrently), continuing", indexName);
            return false;
        }
    }

    // Bumped whenever audit_ism_policy.json changes in a way existing policies must pick up; it's
    // part of the policy's description, so an outdated policy is recognised and updated in place.
    // v2: no rollover - the audit stream rotates indices daily and moves the write alias itself,
    // so an ISM rollover always failed ("Missing alias or not the write index"), ISM disabled the
    // job, and the index stayed in "hot" forever - never reaching its delete transition.
    static final String POLICY_VERSION_MARKER = "kockpit-audit-policy-v2";

    private static final Pattern SEQ_NO = Pattern.compile("\"_seq_no\"\\s*:\\s*(\\d+)");

    private static final Pattern PRIMARY_TERM = Pattern.compile("\"_primary_term\"\\s*:\\s*(\\d+)");

    @SneakyThrows
    void createISMPolicy(Integer ttl, String policyId, String indexPrefix) {
        // The generic client returns 4xx/5xx as a Response, it does not throw: a missing policy is
        // a plain 404 here. (Only creating it from a catch block meant it was never created in
        // normal operation - only when the GET itself blew up, e.g. during shutdown.)
        String existing;
        try (Response response = client.generic()
                    .execute(Requests.builder()
                            .method("GET")
                            .endpoint("_plugins/_ism/policies/" + policyId)
                            .build()
                    )) {
            if (response.getStatus() == 404) {
                doCreatePolicy(policyId, indexPrefix, ttl);
                return;
            }
            if (!isOk(response.getStatus())) {
                log.warn("⚠️ Unexpected status {} checking ISM policy {}, will retry next cycle", response.getStatus(), policyId);
                return;
            }
            existing = response.getBody().map(Body::bodyAsString).orElse("");
        } catch (Exception e) {
            log.warn("⚠️ Could not check ISM policy {}, will retry next cycle: {}", policyId, e.getMessage());
            return;
        }

        if (existing.contains(POLICY_VERSION_MARKER)) {
            log.trace("✅ Policy {} already exists and is up to date", policyId);
            return;
        }
        updatePolicy(policyId, indexPrefix, ttl, existing);
    }

    // Only affects indices created from now on (or attached to this policy later): indices already
    // managed by an older version keep running it, as ISM does for any policy update.
    private void updatePolicy(String policyId, String indexPrefix, Integer ttl, String existing) {
        Matcher seqNo = SEQ_NO.matcher(existing);
        Matcher primaryTerm = PRIMARY_TERM.matcher(existing);
        if (!seqNo.find() || !primaryTerm.find()) {
            log.warn("⚠️ ISM policy {} is outdated but its _seq_no/_primary_term could not be read, not updating it", policyId);
            return;
        }
        log.info("➡️ Updating outdated ISM policy {} to {}", policyId, POLICY_VERSION_MARKER);
        try (Response response = client.generic().execute(Requests.builder()
                .method("PUT")
                .endpoint("_plugins/_ism/policies/" + policyId)
                .query(Map.of("if_seq_no", seqNo.group(1), "if_primary_term", primaryTerm.group(1)))
                .json(loadPolicy(indexPrefix, ttl))
                .build())) {
            if (isOk(response.getStatus())) {
                log.info("✅ Updated ISM policy {}", policyId);
            } else if (response.getStatus() == 409) {
                // Another thread/task updated it first (optimistic concurrency) - re-checked next cycle.
                log.info("✅ ISM policy {} was updated concurrently", policyId);
            } else {
                log.error("❌ Failed to update ISM policy {}: response {}: {}", policyId, response.getStatus(),
                        response.getBody().map(Body::bodyAsString).orElse(null));
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not update ISM policy {}, will retry next cycle: {}", policyId, e.getMessage());
        }
    }

    private boolean isOk(int status) {
        return status / 100 == 2;
    }

    private boolean isCircuitBreaker(int status) {
        return status == 429;
    }

    @SneakyThrows
    private boolean policyExists(String policyId) {
        try (Response response = client.generic().execute(Requests.builder()
                .method("GET")
                .endpoint("_plugins/_ism/policies/" + policyId)
                .build())) {
            return isOk(response.getStatus());
        } catch (Exception e) {
            return false;
        }
    }

    private void doCreatePolicy(String policyId, String indexPrefix, Integer ttl) {
        log.info("➡️ Creating ISM policy with ID: {} and TTL: {}d", policyId, ttl);
        String policyJson = loadPolicy(indexPrefix, ttl);
        try (Response response = client.generic()
                .execute(Requests.builder()
                        .method("PUT")
                        .endpoint("_plugins/_ism/policies/" + policyId)
                        .json(policyJson)
                        .build())
        ) {
            if (isOk(response.getStatus())) {
                log.info("✅ Created policy {}, ttl {}", policyId, ttl);
            } else if (isCircuitBreaker(response.getStatus())) {
                log.warn("⚠️ OpenSearch circuit breaker triggered creating ISM policy {}, will retry next cycle", policyId);
            } else if (policyExists(policyId)) {
                // Same daily-rollover race as createIndexIdempotent(): another thread/shard-
                // processor/task won the race to create this policy first, and the ISM plugin
                // rejects the loser's PUT (commonly with a 400) - not a real failure once the
                // policy is actually there.
                log.info("✅ Policy {} already exists (created concurrently), status {}", policyId, response.getStatus());
            } else {
                log.error("❌ Failed to create ISM policy {} with TTL {}: response {}: {}", policyId, ttl, response.getStatus(), response.getBody().map(Body::bodyAsString).orElse(null));
                throw new RuntimeException("❌ Policy creation failed with status " + response.getStatus());
            }
        } catch (Exception e) {
            log.error("❌ Failed to create ISM policy {} with TTL {}", policyId, ttl, e);
            if (strictMode) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @SneakyThrows
    private String loadPolicy(String indexPrefix, Integer ttl) {
        try (InputStream is = this.getClass().getResourceAsStream(policyResource)) {
            String policyJson = new String(requireNonNull(is).readAllBytes())
                    .replace("${delete_min_index_age}", ttl+"d")
                    .replace("${index_pattern}", indexPrefix + "*");
            log.trace("ISM Policy JSON:\n{}", policyJson);

            return policyJson;
        }
    }

    @SneakyThrows
    private boolean templateExists(String name) {
        try (Response response = client.generic().execute(Requests.builder()
                .method("HEAD")
                .endpoint("_index_template/" + name)
                .build())) {
            return isOk(response.getStatus());
        }
    }

    @SneakyThrows
    void createIndexTemplate(String indexPrefix, String policyId, String aliasWrite) {
        if (templateExists(indexPrefix) || templateExists(indexPrefix + "_template")) {
            log.trace("✅ Template {} already exists, skipping creation", indexPrefix);
            return;
        }
        try (InputStream is = this.getClass().getResourceAsStream(indexTemplateResource)) {

            log.info("➡️ Creating Template {} for policy {}", indexPrefix, policyId);
            String templateJson = new String(requireNonNull(is).readAllBytes())
                    .replace("${index_pattern}", indexPrefix + "*")
                    .replace("${policy_id}", policyId)
                    .replace("${write_alias}", aliasWrite);

            try (Response response = client.generic().execute(Requests.builder()
                    .method("PUT")
                    .endpoint("_index_template/" + indexPrefix)
                    .json(templateJson)
                    .build())) {
                if (isOk(response.getStatus())) {
                    log.info("✅ Template created for indexPrefix {}", indexPrefix);
                } else if (isCircuitBreaker(response.getStatus())) {
                    log.warn("⚠️ OpenSearch circuit breaker triggered creating template {}, will retry next cycle", indexPrefix);
                } else {
                    log.error("❌ Create Template {} failed with status {}: {}", indexPrefix, response.getStatus(), response.getBody().map(Body::bodyAsString).orElse(null));
                    throw new RuntimeException("Create Template " + indexPrefix + " failed with status " + response.getStatus());
                }
            }
        } catch (Exception e) {
            log.error("❌ Failed to create template for indexPrefix {} and policyId {}", indexPrefix, policyId, e);
            if (strictMode) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static final int ALIAS_ATTACH_MAX_ATTEMPTS = 3;

    @SneakyThrows
    private void attachWriteAlias(String indexName, String aliasWrite, String indexPrefix) {
        // Remove-from-old and add-to-new are submitted as ONE atomic _aliases request (both
        // actions apply or neither does), not two separate calls. With two separate calls, a
        // failure on the add (after the remove already succeeded) left the alias attached
        // nowhere - and the next bulk write to that alias name got silently "fixed" by
        // OpenSearch's dynamic index auto-creation, permanently poisoning the alias with a real,
        // unmanaged index (this happened to a -write alias in production).
        // Atomic means a failure here leaves the alias on the OLD index, which is safe: writes
        // keep flowing correctly, just to yesterday's index, until the next successful retry.
        UpdateAliasesRequest request = UpdateAliasesRequest.of(u -> u
                .actions(
                        Action.of(a -> a
                                .remove(RemoveAction.of(r -> r
                                        .indices(indexPrefix + "-*")
                                        .aliases(aliasWrite)
                                ))
                        ),
                        Action.of(a -> a
                                .add(AddAction.of(add -> add
                                        .indices(indexName)
                                        .aliases(aliasWrite)
                                        .isWriteIndex(true)
                                ))
                        )
                )
        );

        Exception lastError = null;
        for (int attempt = 1; attempt <= ALIAS_ATTACH_MAX_ATTEMPTS; attempt++) {
            try {
                client.indices().updateAliases(request);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("⚠️ Attempt {}/{} to attach write alias {} -> {} failed: {}",
                        attempt, ALIAS_ATTACH_MAX_ATTEMPTS, aliasWrite, indexName, e.getMessage());
                if (attempt < ALIAS_ATTACH_MAX_ATTEMPTS) {
                    Thread.sleep(1000L * attempt);
                }
            }
        }
        log.error("❌ Failed to attach write alias {} -> {} after {} attempts. Until this is fixed, " +
                        "bulk writes targeting '{}' will fall through to OpenSearch's dynamic index " +
                        "auto-creation and silently mint a permanent, unmanaged index with that exact name.",
                aliasWrite, indexName, ALIAS_ATTACH_MAX_ATTEMPTS, aliasWrite, lastError);
        throw new RuntimeException("Failed to attach write alias " + aliasWrite + " to " + indexName, lastError);
    }

    @SneakyThrows
    private boolean isWriteAliasCorrect(String indexName, String aliasWrite) {
        try (Response response = client.generic().execute(Requests.builder()
                .method("GET")
                .endpoint("/" + indexName + "/_alias/" + aliasWrite)
                .build())) {
            if (!isOk(response.getStatus())) {
                return false;
            }
            String body = response.getBody().map(Body::bodyAsString).orElse("");
            return body.contains("\"is_write_index\":true");
        } catch (Exception e) {
            return false;
        }
    }

    @SneakyThrows
    private void attachReadAlias(String indexName, String aliasRead) {
        UpdateAliasesRequest request = UpdateAliasesRequest.of(u -> u
                .actions(Action.of(a -> a
                        .add(AddAction.of(add -> add
                                .indices(indexName)
                                .aliases(aliasRead)
                        ))
                ))
        );
        client.indices().updateAliases(request);
    }
}

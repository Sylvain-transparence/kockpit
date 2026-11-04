package org.kockpit.audit.stream.s3;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kockpit.audit.stream.api.AuditConsumer;
import org.kockpit.audit.stream.api.AuditStreamJson;
import org.kockpit.audit.stream.api.model.AuditReport;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Batches consumed {@link AuditReport}s in memory and periodically writes them to S3, grouped by
 * domain/env/appId ({@link S3AuditGroup}) so each object holds reports from a single group, one
 * newline-delimited JSON object per group per flush. Records are serialized with the same
 * {@link AuditStreamJson} mapper used to read the stream, so the archive stays byte-compatible
 * with the wire format.
 */
@Slf4j
@RequiredArgsConstructor
public class S3AuditConsumer implements SmartLifecycle {

    // producer (accept) / consumer (flush) can run on different threads (e.g. one per Kinesis
    // shard); a plain ArrayList would not be safe to share across them.
    //private final Queue<List<S3Record>> auditReports = new ConcurrentLinkedQueue<>();
    //private final Map<S3Key, Queue<S3Record>> auditReports = new ConcurrentHashMap<>();
    private final Map<S3Key, S3Batch> auditReports = new ConcurrentHashMap<>();

    private final S3Client s3Client;

    private final String bucketName;

    private final Integer batchSize;

    private final Integer ttlDefaultInDays;

    // The bucket's S3 lifecycle rules only expire objects tagged with one of these exact
    // "retention=<days>" values - anything else matches no rule and never gets cleaned up. Sorted
    // ascending on construction so snapToAllowedTtl() can rely on ordering regardless of how the
    // config lists them.
    private final List<Integer> allowedTtlDays;

    private final ApplicationEventPublisher eventPublisher;

    private final MeterRegistry meterRegistry;

    // Backpressure limit: how many bytes of not-yet-written records this consumer will hold in
    // auditReports before accept() blocks (see accept()/awaitBufferRoom() below) instead of
    // piling more on top - the actual OOM risk when S3 or OpenSearch falls behind the ingest
    // rate, since neither the 5s scheduled flush() nor an individual key crossing batchSize
    // bounds how many *distinct* keys can be buffered at once.
    private final long maxBufferedBytes;

    // Approximates the heap held by auditReports (record bytes only, not the batch/map
    // bookkeeping) without walking the whole map on every accept() call. Incremented as records
    // are added in accept(), decremented once a written batch is discarded in flushKey() - never
    // for the leftover records flushKey() requeues, since those stay resident.
    private final AtomicLong bufferedBytes = new AtomicLong();

    // Monitor for awaitBufferRoom()/flushKey() to wait/notify on - separate from bufferedBytes
    // itself (a lock-free AtomicLong) since a monitor is what wait()/notifyAll() need.
    private final Object bufferLock = new Object();

    // How long an accept() blocked on a full buffer waits before re-checking and re-logging that
    // it's still blocked - not a timeout to give up on, just how often to prove it hasn't
    // deadlocked. A real drain (flushKey()'s notifyAll()) wakes it immediately regardless.
    private static final long BUFFER_WAIT_POLL_MS = 2000;

    private volatile boolean running;

    @PostConstruct
    public void init() {
        log.info("✅ S3 Audit consumer started, writing batches to bucket {}", bucketName);
        registerMetrics();
    }

    @Override
    public void start() {
        running = true;
    }

    // Drains on context close, after the stream readers have stopped (see
    // AuditConsumer.SHUTDOWN_DRAIN_PHASE) and before s3Client is closed - a JVM shutdown hook
    // raced Spring's own and lost, failing every final write with "Connection pool shut down".
    @Override
    public void stop() {
        log.info("🛑 Draining S3 audit consumer before shutdown");
        drain();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return AuditConsumer.SHUTDOWN_DRAIN_PHASE;
    }

    // This buffer previously had no external visibility at all - the only way to learn it was
    // growing unboundedly was an OOM kill, hours after the fact. These gauges let an alert fire
    // on buffered.bytes approaching maxBufferedBytes long before that, instead of after.
    private void registerMetrics() {
        Gauge.builder("kockpit.audit.stream.s3.buffer.bytes", bufferedBytes, AtomicLong::get)
                .description("Bytes currently buffered in memory awaiting S3 archival")
                .register(meterRegistry);
        Gauge.builder("kockpit.audit.stream.s3.buffer.bytes.max", () -> maxBufferedBytes)
                .description("Configured backpressure limit for buffer.bytes; accept() blocks once reached")
                .register(meterRegistry);
        Gauge.builder("kockpit.audit.stream.s3.buffer.records", auditReports,
                        map -> map.values().stream().mapToInt(S3Batch::size).sum())
                .description("Audit records currently buffered in memory awaiting S3 archival")
                .register(meterRegistry);
        Gauge.builder("kockpit.audit.stream.s3.buffer.keys", auditReports, Map::size)
                .description("Distinct domain/env/appId/ttl keys currently buffered")
                .register(meterRegistry);
    }

    public void accept(List<byte[]> events) {
        // Blocks the caller (an EFO/non-EFO Kinesis record processor, on its own processing
        // thread) rather than accepting-then-rejecting: for a push-based subscription, an
        // exception here doesn't stop the subscription from advancing to the next batch of
        // records regardless - only NOT RETURNING does, since that's what holds up the
        // subscription's next `request(1)`/checkpoint. See awaitBufferRoom().
        awaitBufferRoom(events.size());

        events.stream().map(bytes -> new S3Record(bytes, S3Key.read(bytes)))
                // Some producers already offload the full report to their own S3 object before
                // publishing to Kinesis (see S3Key.existingS3Key) - archiving it again here would
                // both waste a write and, worse, give a caller (e.g. OpensearchS3AuditConsumer)
                // a reason to overwrite that record's only pointer to its full self with one
                // pointing at this consumer's strictly-worse (root-only) archived copy.
                .filter(record -> record.getS3Key().getExistingS3Key() == null)
                .collect(Collectors.groupingBy(S3Record::getS3Key))
                .forEach((k, v) -> {
                    for (S3Record record : v) {
                        // Reading-the-current-batch and adding to it must happen as one atomic
                        // step against the map, not get()-then-add() - otherwise a concurrent
                        // swapForFreshBatch() (flushKey) or pruneEmptyBatches() compute() on this
                        // same key could run in between, and this add() would either land in an
                        // orphaned batch nobody will ever poll again (silent data loss) or NPE on
                        // a batch pruneEmptyBatches just removed. compute() serializes against
                        // both, since ConcurrentHashMap never interleaves compute-family calls
                        // for the same key.
                        int[] sizeAfterAdd = new int[1];
                        auditReports.compute(k, (kk, batch) -> {
                            S3Batch b = batch != null ? batch : new S3Batch();
                            sizeAfterAdd[0] = b.add(record);
                            return b;
                        });
                        bufferedBytes.addAndGet(record.getData().length);
                        // Flushing the moment a key's queue crosses a batchSize boundary (not
                        // just on the fixed schedule) bounds how large it can grow between ticks
                        // for a hot key outpacing the scheduler - the unbounded-growth OOM this
                        // consumer hit before was exactly that, not stale keys (which
                        // pruneEmptyBatches already handles).
                        if (sizeAfterAdd[0] % batchSize == 0) {
                            flushKey(k);
                        }
                    }
                });
    }

    // Checked once per accept() call, for the whole incoming batch, not per-record: Kinesis/Kafka
    // callers can only checkpoint/ack past a batch they fully processed, never "processed all but
    // the records I silently dropped" - so once we decide to accept, we always accept the whole
    // thing, even if that pushes bufferedBytes somewhat over maxBufferedBytes (the gate re-engages
    // on the next call either way).
    private void awaitBufferRoom(int incomingRecordCount) {
        synchronized (bufferLock) {
            long currentBytes = bufferedBytes.get();
            if (currentBytes < maxBufferedBytes) {
                return;
            }
            log.warn("⚠️ S3 audit consumer buffer full ({} bytes buffered, limit {}) - blocking {} incoming " +
                    "record(s) until S3/OpenSearch drain it", currentBytes, maxBufferedBytes, incomingRecordCount);
            while (currentBytes >= maxBufferedBytes) {
                try {
                    bufferLock.wait(BUFFER_WAIT_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new S3ConsumerBackpressureException(currentBytes, maxBufferedBytes, e);
                }
                currentBytes = bufferedBytes.get();
                if (currentBytes >= maxBufferedBytes) {
                    log.warn("⚠️ Still blocked on a full S3 audit consumer buffer ({} bytes buffered, limit {})",
                            currentBytes, maxBufferedBytes);
                }
            }
        }
    }

    private void write(List<S3Record> batch, S3Key prefix) {
        int ttl = resolveTtl(batch);
        String s3Key;
        try {
            assignOffsets(batch);
            s3Key = prefix.toString() + "/" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".batch";
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Key)
                            .tagging("retention=%d".formatted(ttl))
                            .build(),
                    RequestBody.fromBytes(concatenate(batch))
            );
        } catch (Exception e) {
            log.error("❌ Failed to write {} audit reports to s3://{}/{}: {}", batch.size(), bucketName, prefix, e.getMessage(), e);
            return;
        }

        // The object is already in S3 at this point: a failure here comes from the S3WriteEvent
        // listeners (e.g. opensearch-s3 indexing the offloaded reports), not from S3 - reporting it
        // as an S3 write failure hid an OpenSearch client closed too early on shutdown.
        try {
            eventPublisher.publishEvent(new S3WriteEvent(this, s3Key, batch, bucketName, ttl));
            log.trace("Wrote {} audit reports to s3://{}/{}", batch.size(), bucketName, s3Key);
        } catch (Exception e) {
            log.error("❌ Wrote {} audit reports to s3://{}/{} but post-write processing (e.g. OpenSearch indexing) failed: {}",
                    batch.size(), bucketName, s3Key, e.getMessage(), e);
        }
    }

    // One S3 object can hold reports from several AuditReports with different ttl values (S3
    // lifecycle/retention tagging applies per-object, not per-record), so the object's retention
    // must cover the longest-lived report in it - hence the max, not e.g. the first record's ttl.
    private int resolveTtl(List<S3Record> batch) {
        int ttl = batch.stream()
                .mapToInt(this::readTtl)
                .max()
                .orElse(ttlDefaultInDays);
        return snapToAllowedTtl(ttl);
    }

    // Rounds UP to the smallest configured value that still covers the requested retention (never
    // down - under-retaining audit data is worse than over-retaining it), capping at the longest
    // available bucket if the request exceeds all of them.
    private int snapToAllowedTtl(int requestedDays) {
        List<Integer> sorted = allowedTtlDays.stream().sorted().toList();
        for (int allowed : sorted) {
            if (requestedDays <= allowed) {
                return allowed;
            }
        }
        int longest = sorted.get(sorted.size() - 1);
        log.warn("⚠️ Requested ttl {}d exceeds the longest available retention bucket ({}d), capping to it",
                requestedDays, longest);
        return longest;
    }

    // record.getS3Key().getTtl() was already parsed once from this record's bytes in accept()
    // (S3Key.read) - no need to decompress/parse the full AuditReport a second time just for ttl.
    private int readTtl(S3Record record) {
        Integer ttl = record.getS3Key().getTtl();
        return ttl != null ? ttl : ttlDefaultInDays;
    }

    // Position/length within THIS specific object, not cumulative across the key's lifetime -
    // each flush() writes a brand-new, separate S3 object, so offsets must restart at 0 here.
    private static void assignOffsets(List<S3Record> batch) {
        long offset = 0;
        for (S3Record record : batch) {
            record.setOffset(offset);
            record.setLength(record.getData().length);
            offset += record.getData().length;
        }
    }

    static byte[] concatenate(List<S3Record> data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        data.stream().map(S3Record::getData).forEach(out::writeBytes);
        return out.toByteArray();
    }

    @Scheduled(
            fixedDelayString = "${kockpit.audit.stream.s3.scheduler_ms:5000}",
            timeUnit = TimeUnit.MILLISECONDS)
    void flush() {
        for (S3Key key : auditReports.keySet()) {
            flushKey(key);
        }
        pruneEmptyBatches();
    }

    // Unlike flush(), which writes at most batchSize per key per call and requeues the rest,
    // keeps flushing until nothing is buffered - for shutdown/checkpoint, where anything left
    // behind is lost. Not synchronized: concurrent callers just split the keys between them.
    public void drain() {
        while (auditReports.values().stream().anyMatch(batch -> !batch.isEmpty())) {
            flush();
        }
    }

    // Swaps out whatever's queued for this key and writes up to batchSize of it, requeuing any
    // leftover. Called both for every key on the fixed schedule, and inline from accept() the
    // instant a key's queue crosses a batchSize boundary, so a hot key can't outgrow batchSize
    // between ticks.
    private void flushKey(S3Key key) {
        S3Batch oldBatch = swapForFreshBatch(key);
        if (oldBatch == null) {
            return;
        }
        // Cap this write at batchSize, same as before - anything beyond that stays queued
        // (requeued below) rather than growing one S3 object without bound.
        List<S3Record> batch = new ArrayList<>();
        S3Record record;
        for (int i = 0; i < batchSize && (record = oldBatch.poll()) != null; i++) {
            batch.add(record);
        }
        // Requeue any leftover beyond batchSize onto whatever's currently there for this key -
        // may already hold records accept() added after the swap below.
        S3Record leftover;
        while ((leftover = oldBatch.poll()) != null) {
            auditReports.computeIfAbsent(key, k -> new S3Batch()).add(leftover);
        }
        write(batch, key);
        // Freed regardless of whether write() actually succeeded - it already catches and only
        // logs its own failures (see write()), so these bytes are gone from auditReports (and,
        // on failure, from anywhere at all) either way.
        bufferedBytes.addAndGet(-batch.stream().mapToLong(r -> r.getData().length).sum());
        // Wakes any accept() call(s) parked in awaitBufferRoom() so they re-check immediately
        // rather than waiting out the rest of their poll interval.
        synchronized (bufferLock) {
            bufferLock.notifyAll();
        }
    }

    // Atomically replaces this key's batch with a fresh empty one and returns the batch being
    // replaced, exclusively ours from this point on - any accept() call that already holds a
    // reference to it (mid-add) still completes safely (ConcurrentLinkedQueue is thread-safe for
    // that), and any accept() call after this swap gets the new instance instead. Closes the
    // accept()/flush() race that a drain-in-place poll() loop has: no window where a record can
    // land in a batch this cycle has already decided is empty and is about to prune.
    private S3Batch swapForFreshBatch(S3Key key) {
        S3Batch[] previous = new S3Batch[1];
        auditReports.compute(key, (k, current) -> {
            previous[0] = current;
            return new S3Batch();
        });
        return (previous[0] == null || previous[0].isEmpty()) ? null : previous[0];
    }

    // Without this, auditReports grows without bound for the lifetime of the process - one entry
    // per distinct (domain, env, appId, ttl) combination ever seen. On a shared, multi-tenant
    // stream with ephemeral per-MR review environments constantly rotating through, that's
    // effectively unbounded growth (the direct cause of an eventual "Java heap space" OOM,
    // regardless of how much heap is available - it only changes how long it takes).
    private void pruneEmptyBatches() {
        auditReports.keySet().forEach(key ->
                auditReports.computeIfPresent(key, (k, batch) -> batch.isEmpty() ? null : batch));
    }
}

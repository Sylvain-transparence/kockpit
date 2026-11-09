import { AsyncLocalStorage } from 'node:async_hooks';
import { createHash, randomUUID } from 'node:crypto';
import { hostname } from 'node:os';
import { gzipSync } from 'node:zlib';

export type AuditValue =
  string | number | boolean | null | AuditValue[] | { [key: string]: AuditValue };
export type AuditEvent = Record<string, AuditValue>;
export interface AuditReport {
  id: string;
  domain: string;
  env: string;
  requestId: string;
  appId: string;
  hostname: string;
  version: string;
  artifact: string;
  /** Kockpit Instant: epoch seconds, NOT JavaScript milliseconds. */
  start: number;
  end: number;
  indexedKeyValues: { key: string; value: string }[];
  audits: { type: string; events: AuditEvent[] }[];
}
export interface AuditRecord {
  id: string;
  /** Same partition key as the Java Kafka SDK: domain-env-appId. */
  key: string;
  value: Buffer;
}
export interface AuditOutbox {
  bind(destination: string): void;
  append(record: AuditRecord): boolean;
  next(now: number): AuditRecord | undefined;
  acknowledge(id: string): void;
  defer(id: string, now: number, baseMs: number): number;
  stats(): { pending: number; bytes: number };
  close(): void;
}
export interface AuditTransport {
  send(record: AuditRecord): Promise<void>;
  close?(): Promise<void>;
}
export interface AuditClientOptions {
  domain: string;
  env: string;
  appId: string;
  transport: AuditTransport;
  outbox?: AuditOutbox;
  /** Stable transport destination, required when an outbox is configured. */
  outboxDestination?: string;
  retryIntervalMs?: number;
  artifact?: string;
  hostname?: string;
  gzip?: boolean;
  maxQueueSize?: number;
  maxReportBytes?: number;
  maxEvents?: number;
  onDiagnostic?: (
    code: 'queue_full' | 'report_rejected' | 'transport_failed' | 'outbox_full' | 'outbox_failed',
  ) => void;
}
export interface AuditStart {
  requestId?: string;
  indexed?: Record<string, string>;
  /** Optional original start, in epoch milliseconds, for an already completed operation. */
  startTimeMs?: number;
}

function epochSeconds(milliseconds: number): number {
  if (!Number.isFinite(milliseconds) || milliseconds < 0 || milliseconds > 8640000000000000)
    throw new Error('Invalid audit timestamp');
  return milliseconds / 1000;
}

function boundedInteger(value: number, min: number, max: number): number {
  if (!Number.isInteger(value) || value < min || value > max)
    throw new Error('Invalid audit limit');
  return value;
}
function text(value: string, limit = 1024): string {
  if (typeof value !== 'string' || !value.length || value.length > limit)
    throw new Error('Invalid audit text');
  return value;
}
function eventCopy(event: AuditEvent): AuditEvent {
  if (!event || typeof event !== 'object' || Array.isArray(event))
    throw new Error('Audit events must be JSON objects');
  let nodes = 0,
    bytes = 0;
  const visit = (value: AuditValue, depth: number): AuditValue => {
    if (++nodes > 4096 || depth > 10) throw new Error('Audit structure too large');
    if (typeof value === 'string') {
      if (value.length > 32768) throw new Error('Audit value too long');
      bytes += Buffer.byteLength(value);
    } else if (typeof value === 'number') {
      if (!Number.isFinite(value)) throw new Error('Invalid audit number');
    } else if (Array.isArray(value)) {
      if (value.length > 100) throw new Error('Too many audit array items');
      return value.map((item) => visit(item, depth + 1));
    } else if (value && typeof value === 'object') {
      if (
        Object.getPrototypeOf(value) !== Object.prototype &&
        Object.getPrototypeOf(value) !== null
      )
        throw new Error('Audit objects must be plain JSON');
      const entries = Object.entries(value);
      if (entries.length > 32) throw new Error('Too many audit event fields');
      return Object.fromEntries(
        entries.map(([key, item]) => {
          text(key, 128);
          bytes += Buffer.byteLength(key);
          if (bytes > 131072) throw new Error('Audit event too large');
          return [key, visit(item, depth + 1)];
        }),
      );
    } else if (value !== null && typeof value !== 'boolean')
      throw new Error('Audit values must be JSON');
    if (bytes > 131072) throw new Error('Audit event too large');
    return value;
  };
  return visit(event, 0) as AuditEvent;
}

export class AuditSpan {
  private readonly groups = new Map<string, AuditEvent[]>();
  private readonly indexed = new Map<string, string>();
  private count = 0;
  private bytes = 0;
  private truncated = 0;
  private readonly droppedByType = new Map<string, number>();
  private reservedEvents = 0;
  private reservedBytes = 0;
  private regularCount = 0;
  private regularBytes = 0;
  private finished = false;
  readonly id = randomUUID();
  private startedAt: number;
  get start() {
    return this.startedAt;
  }
  constructor(
    private readonly client: AuditClient,
    readonly requestId: string,
    private readonly maxEvents: number,
    private readonly maxBytes: number,
    startTimeMs = Date.now(),
  ) {
    this.startedAt = epochSeconds(startTimeMs);
  }
  /** Include an earlier correlated event before the report is finalized. */
  includeStart(startTimeMs: number): this {
    if (!this.finished) this.startedAt = Math.min(this.startedAt, epochSeconds(startTimeMs));
    return this;
  }
  index(key: string, value: string): this {
    if (this.finished) return this;
    text(key, 128);
    text(value);
    if (!this.indexed.has(key) && this.indexed.size >= 32)
      throw new Error('Too many audit indexes');
    this.indexed.set(key, value);
    return this;
  }
  /** Reserve capacity before adding events; essential admission still obeys the total limits. */
  reserveEssential(events: number, bytes: number): this {
    if (this.finished || this.count) throw new Error('Reserve audit capacity before events');
    this.reservedEvents = Math.min(boundedInteger(events, 0, 10000), this.maxEvents);
    this.reservedBytes = Math.min(boundedInteger(bytes, 0, 1048576), this.maxBytes / 2);
    return this;
  }
  private drop(type: string): false {
    this.truncated++;
    const key = this.droppedByType.has(type) || this.droppedByType.size < 16 ? type : '_other';
    this.droppedByType.set(key, (this.droppedByType.get(key) ?? 0) + 1);
    return false;
  }
  event(type: string, event: AuditEvent): this {
    this.tryEvent(type, event);
    return this;
  }
  /** Returns false for invalid or oversized metadata without interrupting the caller. */
  tryEvent(type: string, event: AuditEvent, essential = false): boolean {
    return this.addEvent(type, event, essential, false);
  }
  /** Validate event metadata strictly, while retaining the usual capacity limits. */
  eventStrict(type: string, event: AuditEvent): this {
    this.addEvent(type, event, false, true);
    return this;
  }
  private addEvent(type: string, event: AuditEvent, essential: boolean, strict: boolean): boolean {
    if (this.finished) return false;
    let copy: AuditEvent;
    try {
      text(type, 128);
      if (
        this.count >= this.maxEvents ||
        (!essential && this.regularCount >= this.maxEvents - this.reservedEvents)
      )
        return this.drop(type);
      copy = eventCopy(event);
    } catch (error) {
      if (strict) throw error;
      return this.drop('_invalid');
    }
    const size = Buffer.byteLength(JSON.stringify(copy)) + Buffer.byteLength(type) + 64;
    if (
      this.bytes + size > this.maxBytes / 2 ||
      (!essential && this.regularBytes + size > this.maxBytes / 2 - this.reservedBytes)
    )
      return this.drop(type);
    const events = this.groups.get(type) ?? [];
    events.push(copy);
    this.groups.set(type, events);
    this.bytes += size;
    this.count++;
    if (!essential) {
      this.regularCount++;
      this.regularBytes += size;
    }
    return true;
  }
  /** Idempotent. Queues a snapshot without awaiting the transport. */
  finish(endTimeMs = Date.now()): boolean {
    if (this.finished) return false;
    const end = Math.max(this.start, epochSeconds(endTimeMs));
    this.finished = true;
    if (this.truncated)
      this.groups.set('sdk.truncated', [
        { droppedEvents: this.truncated, droppedByType: Object.fromEntries(this.droppedByType) },
      ]);
    return this.client.submit({
      id: this.id,
      requestId: this.requestId,
      start: this.start,
      end,
      indexedKeyValues: [...this.indexed].map(([key, value]) => ({ key, value })),
      audits: [...this.groups].map(([type, events]) => ({ type, events })),
    });
  }
}

/** Bounded, best-effort exporter. It never retries business operations. */
export class AuditClient {
  private static readonly ownedOutboxes = new WeakSet<AuditOutbox>();
  private readonly context = new AsyncLocalStorage<AuditSpan>();
  private readonly queue: AuditRecord[] = [];
  private readonly identity: Pick<
    AuditReport,
    'domain' | 'env' | 'appId' | 'hostname' | 'artifact' | 'version'
  >;
  private readonly maxQueueSize: number;
  private readonly maxReportBytes: number;
  private readonly maxEvents: number;
  private worker?: Promise<void>;
  private inFlight = false;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private readonly retryIntervalMs: number;
  private blockedUntil = 0;
  private stopping = false;
  private outboxClosed = false;
  private durableStats = { pending: 0, bytes: 0 };
  private accepting = true;
  private closing?: Promise<void>;
  private counters = { accepted: 0, sent: 0, failed: 0, dropped: 0 };
  constructor(private readonly options: AuditClientOptions) {
    this.identity = {
      domain: text(options.domain, 128),
      env: text(options.env, 128),
      appId: text(options.appId, 128),
      hostname: text(options.hostname ?? hostname(), 256),
      artifact: text(options.artifact ?? options.appId, 256),
      version: 'V2_6',
    };
    this.maxQueueSize = boundedInteger(options.maxQueueSize ?? 100, 1, 10000);
    this.maxReportBytes = boundedInteger(options.maxReportBytes ?? 262144, 4096, 1048576);
    this.maxEvents = boundedInteger(options.maxEvents ?? 1000, 1, 10000);
    this.retryIntervalMs = boundedInteger(options.retryIntervalMs ?? 1000, 10, 30000);
    if (options.outbox) {
      if (AuditClient.ownedOutboxes.has(options.outbox))
        throw new Error('Audit outbox is already in use');
      try {
        const destination = text(options.outboxDestination ?? '');
        options.outbox.bind(
          createHash('sha256')
            .update(
              JSON.stringify([
                this.identity.domain,
                this.identity.env,
                this.identity.appId,
                destination,
              ]),
            )
            .digest('hex'),
        );
        this.durableStats = options.outbox.stats();
      } catch (error) {
        options.outbox.close();
        throw error;
      }
      AuditClient.ownedOutboxes.add(options.outbox);
      this.schedule(); // Replay persisted records without waiting for a new audit.
    }
  }
  start(input: AuditStart = {}): AuditSpan {
    const span = new AuditSpan(
      this,
      text(input.requestId ?? randomUUID()),
      this.maxEvents,
      this.maxReportBytes,
      input.startTimeMs,
    );
    for (const [key, value] of Object.entries(input.indexed ?? {})) span.index(key, value);
    span.index('traceId', span.requestId);
    return span;
  }
  current(): AuditSpan | undefined {
    return this.context.getStore();
  }
  withSpan<T>(span: AuditSpan, work: () => Promise<T>): Promise<T> {
    return this.context.run(span, work);
  }
  async run<T>(input: AuditStart, work: (span: AuditSpan) => Promise<T>): Promise<T> {
    const span = this.start(input);
    return this.context.run(span, async () => {
      try {
        return await work(span);
      } catch (error) {
        // No exception message/stack: these can contain credentials or provider data.
        span.event('sdk.execution', { outcome: 'error' });
        throw error;
      } finally {
        span.finish();
      }
    });
  }
  stats() {
    if (this.options.outbox) {
      if (!this.outboxClosed) this.durableStats = this.options.outbox.stats();
      return {
        ...this.counters,
        pending: this.durableStats.pending,
        durable: true,
        outboxBytes: this.durableStats.bytes,
      };
    }
    return { ...this.counters, pending: this.queue.length + (this.inFlight ? 1 : 0) };
  }
  private diagnostic(code: Parameters<NonNullable<AuditClientOptions['onDiagnostic']>>[0]) {
    try {
      void Promise.resolve(this.options.onDiagnostic?.(code)).catch(() => {});
    } catch {
      /* Export diagnostics must not break callers. */
    }
  }
  /** Internal submission used by AuditSpan. */
  submit(report: Omit<AuditReport, keyof AuditClient['identity']>): boolean {
    if (
      !this.accepting ||
      (!this.options.outbox && this.queue.length + (this.inFlight ? 1 : 0) >= this.maxQueueSize)
    ) {
      this.counters.dropped++;
      this.diagnostic('queue_full');
      return false;
    }
    try {
      const json = Buffer.from(JSON.stringify({ ...this.identity, ...report }));
      if (json.length > this.maxReportBytes) throw new Error('Report too large');
      const record: AuditRecord = {
        id: report.id,
        key: `${this.identity.domain}-${this.identity.env}-${this.identity.appId}`,
        value: this.options.gzip ? gzipSync(json) : json,
      };
      if (this.options.outbox) {
        try {
          if (!this.options.outbox.append(record)) {
            this.counters.dropped++;
            this.diagnostic('outbox_full');
            return false;
          }
        } catch {
          this.counters.dropped++;
          this.diagnostic('outbox_failed');
          return false;
        }
      } else this.queue.push(record);
      this.counters.accepted++;
      this.schedule();
      return true;
    } catch {
      this.counters.dropped++;
      this.diagnostic('report_rejected');
      return false;
    }
  }
  private schedule() {
    if (this.worker || this.stopping) return;
    if (this.options.outbox && Date.now() < this.blockedUntil) {
      this.retryLater();
      return;
    }
    clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    // Start outside the business call stack, including synchronous transport failures.
    this.worker = Promise.resolve()
      .then(() => (this.options.outbox ? this.drainOutbox() : this.drain()))
      .catch(() => {
        this.diagnostic('outbox_failed');
      })
      .finally(() => {
        this.worker = undefined;
        // Submission can happen between drain resolving and this finally callback.
        if (this.options.outbox) {
          if (!this.stopping) {
            try {
              if (this.options.outbox.stats().pending) this.retryLater();
            } catch {
              this.diagnostic('outbox_failed');
              this.retryLater();
            }
          }
        } else if (this.queue.length) this.schedule();
      });
  }
  private retryLater() {
    if (this.stopping || this.retryTimer) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      this.schedule();
    }, this.retryIntervalMs);
    this.retryTimer.unref();
  }
  private async drainOutbox() {
    const outbox = this.options.outbox!;
    while (!this.stopping) {
      const record = outbox.next(Date.now());
      if (!record) return;
      try {
        await this.options.transport.send(record);
      } catch {
        this.counters.failed++;
        this.diagnostic('transport_failed');
        this.blockedUntil = outbox.defer(record.id, Date.now(), this.retryIntervalMs);
        return;
      }
      // A crash before local acknowledgement can replay this same ID.
      try {
        outbox.acknowledge(record.id);
        this.counters.sent++;
      } catch {
        this.counters.failed++;
        this.diagnostic('outbox_failed');
        this.blockedUntil = outbox.defer(record.id, Date.now(), this.retryIntervalMs);
        return;
      }
    }
  }
  private async drain() {
    let record: AuditRecord | undefined;
    while ((record = this.queue.shift())) {
      this.inFlight = true;
      try {
        await this.options.transport.send(record);
        this.counters.sent++;
      } catch {
        this.counters.failed++;
        this.diagnostic('transport_failed');
      } finally {
        this.inFlight = false;
      }
    }
  }
  private async within(task: Promise<unknown>, timeoutMs: number): Promise<boolean> {
    boundedInteger(timeoutMs, 1, 60000);
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        task.then(
          () => true,
          () => false,
        ),
        new Promise<boolean>((resolve) => {
          timer = setTimeout(() => resolve(false), timeoutMs);
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  }
  async flush(timeoutMs = 5000): Promise<boolean> {
    if (!this.options.outbox) return this.within(this.waitAll(), timeoutMs);
    boundedInteger(timeoutMs, 1, 60000);
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.stats().pending === 0) return true;
      if (this.stopping) return false;
      this.schedule();
      await new Promise<void>((resolve) =>
        setTimeout(resolve, Math.min(25, deadline - Date.now())),
      );
    }
    return this.stats().pending === 0;
  }
  private async waitAll() {
    while (this.worker) await this.worker;
  }
  /** A timeout does not cancel an in-flight transport call; configure transport timeouts too. */
  async close(timeoutMs = 5000): Promise<boolean> {
    this.accepting = false;
    if (this.options.outbox) {
      this.stopping = true;
      clearTimeout(this.retryTimer);
      this.retryTimer = undefined;
      this.closing ??= (async () => {
        await this.waitAll();
        try {
          await this.options.transport.close?.();
        } finally {
          this.durableStats = this.options.outbox!.stats();
          this.options.outbox!.close();
          this.outboxClosed = true;
          AuditClient.ownedOutboxes.delete(this.options.outbox!);
        }
      })();
      return this.within(this.closing, timeoutMs);
    }
    this.closing ??= (async () => {
      await this.waitAll();
      await this.options.transport.close?.();
    })();
    return this.within(this.closing, timeoutMs);
  }
}

export { recordIncomingHttp, recordOutgoingHttp, type FilteredHttpExchange } from './http';

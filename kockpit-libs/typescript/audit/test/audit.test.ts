import { describe, expect, it, vi } from 'vitest';
import { gunzipSync } from 'node:zlib';
import { AuditClient, type AuditRecord, type AuditReport } from '../src';

function capture(options: Partial<ConstructorParameters<typeof AuditClient>[0]> = {}) {
  const records: AuditRecord[] = [];
  const client = new AuditClient({
    domain: 'pms',
    env: 'test',
    appId: 'studio',
    hostname: 'test-host',
    transport: {
      async send(record) {
        records.push(record);
      },
    },
    ...options,
  });
  return { client, records };
}
function decode(record: AuditRecord): AuditReport {
  return JSON.parse(
    (record.value[0] === 0x1f ? gunzipSync(record.value) : record.value).toString(),
  );
}

describe('Kockpit audit stream contract', () => {
  it('drops invalid event metadata without changing the business result', async () => {
    const { client, records } = capture();
    await expect(
      client.run({}, async (span) => {
        span.event('bad', { date: new Date() } as never);
        expect(span.tryEvent('bad', { status: Number.NaN })).toBe(false);
        span.event('valid', { status: 'READY' });
        return 42;
      }),
    ).resolves.toBe(42);
    expect(await client.flush()).toBe(true);
    expect(decode(records[0]).audits).toEqual([
      { type: 'valid', events: [{ status: 'READY' }] },
      { type: 'sdk.truncated', events: [{ droppedEvents: 2, droppedByType: { _invalid: 2 } }] },
    ]);
    await client.close();
  });
  it('rejects invalid imported timestamps without finishing the span', async () => {
    const { client, records } = capture();
    expect(() => client.start({ startTimeMs: NaN })).toThrow('Invalid audit timestamp');
    const span = client.start({ startTimeMs: 1793984400000 });
    expect(() => span.finish(Infinity)).toThrow('Invalid audit timestamp');
    expect(span.finish(1793984400042)).toBe(true);
    await client.flush();
    expect(decode(records[0]).end).toBe(1793984400.042);
    await client.close();
  });
  it.each([false, true])(
    'serializes the consumer model with epoch seconds, grouped events and gzip=%s',
    async (gzip) => {
      const { client, records } = capture({ gzip });
      vi.useFakeTimers();
      try {
        vi.setSystemTime(new Date('2025-06-17T22:06:14.047Z'));
        const span = client.start({ requestId: 'preview-123', indexed: { kind: 'SOURCE_CODES' } });
        span.event('pms.execution', { status: 'PREVIEWING' });
        vi.setSystemTime(new Date('2025-06-17T22:06:18.958Z'));
        span.event('pms.execution', { status: 'READY' });
        expect(span.finish()).toBe(true);
        expect(span.finish()).toBe(false);
        await client.flush();
        expect(records).toHaveLength(1);
        expect(records[0].key).toBe('pms-test-studio');
        expect(decode(records[0])).toEqual({
          id: span.id,
          domain: 'pms',
          env: 'test',
          appId: 'studio',
          hostname: 'test-host',
          artifact: 'studio',
          version: 'V2_6',
          requestId: 'preview-123',
          start: Date.parse('2025-06-17T22:06:14.047Z') / 1000,
          end: Date.parse('2025-06-17T22:06:18.958Z') / 1000,
          indexedKeyValues: [
            { key: 'kind', value: 'SOURCE_CODES' },
            { key: 'traceId', value: 'preview-123' },
          ],
          audits: [
            { type: 'pms.execution', events: [{ status: 'PREVIEWING' }, { status: 'READY' }] },
          ],
        });
      } finally {
        vi.useRealTimers();
        await client.close();
      }
    },
  );
});

describe('Bounded asynchronous export', () => {
  it('snapshots nested HTTP data independently from later mutations', async () => {
    const { client, records } = capture();
    const event = {
      httpAuditedRequest: { method: 'GET', params: { limit: ['1'] } },
      httpAuditedResponse: { status: 200, payload: '{"settings":[]}' },
    };
    const span = client.start();
    span.event('builtin.httpexchanges', event);
    event.httpAuditedRequest.params.limit[0] = 'PRIVATE_MUTATION';
    span.finish();
    await client.flush();
    expect(decode(records[0]).audits[0].events[0]).toMatchObject({
      httpAuditedRequest: { params: { limit: ['1'] } },
    });
    expect(records[0].value.toString()).not.toContain('PRIVATE_MUTATION');
    await client.close();
  });
  it('rejects circular, overdeep, non-JSON and oversized nested data', async () => {
    const { client } = capture();
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    for (const bad of [
      circular,
      new Date(),
      { payload: 'x'.repeat(32769) },
      { items: Array(101).fill('x') },
      { value: Infinity },
    ])
      expect(() => client.start().eventStrict('bad', bad as never)).toThrow();
    let deep: unknown = 'leaf';
    for (let i = 0; i < 12; i++) deep = { child: deep };
    expect(() => client.start().eventStrict('bad', deep as never)).toThrow();
    await client.close();
  });
  it('isolates concurrent and nested contexts and preserves application results', async () => {
    const { client, records } = capture();
    await Promise.all(
      ['a', 'b'].map((requestId) =>
        client.run({ requestId }, async (span) => {
          await Promise.resolve();
          expect(client.current()).toBe(span);
          await client.run({ requestId: requestId + '-child' }, async (child) => {
            expect(client.current()).toBe(child);
            child.event('child', { owner: requestId });
          });
          expect(client.current()).toBe(span);
          span.event('parent', { owner: requestId });
          return 42;
        }),
      ),
    );
    expect(client.current()).toBeUndefined();
    await client.flush();
    const reports = records.map(decode);
    expect(reports).toHaveLength(4);
    for (const report of reports)
      expect(report.audits[0].events[0].owner).toBe(report.requestId[0]);
    await client.close();
  });
  it('preserves the same business exception and never exports its sensitive text', async () => {
    const { client, records } = capture();
    const failure = new Error('SECRET_PROVIDER_RESPONSE');
    await expect(
      client.run({}, async () => {
        throw failure;
      }),
    ).rejects.toBe(failure);
    await client.flush();
    expect(decode(records[0]).audits).toEqual([
      { type: 'sdk.execution', events: [{ outcome: 'error' }] },
    ]);
    expect(records[0].value.toString()).not.toContain('SECRET');
    await client.close();
  });
  it('bounds the queue including the in-flight report, reports overflow and bounds shutdown', async () => {
    let release!: () => void;
    const blocked = new Promise<void>((resolve) => {
      release = resolve;
    });
    const diagnostics: string[] = [];
    const send = vi.fn(() => blocked);
    const { client } = capture({
      transport: { send },
      maxQueueSize: 2,
      onDiagnostic: (code) => diagnostics.push(code),
    });
    expect(client.start().finish()).toBe(true);
    expect(client.start().finish()).toBe(true);
    await Promise.resolve();
    expect(client.start().finish()).toBe(false);
    expect(client.stats().pending).toBe(2);
    expect(await client.flush(10)).toBe(false);
    expect(await client.close(10)).toBe(false);
    expect(send).toHaveBeenCalledTimes(1);
    release();
    expect(await client.close()).toBe(true);
    expect(client.stats()).toEqual({ accepted: 2, sent: 2, failed: 0, dropped: 1, pending: 0 });
    expect(diagnostics).toEqual(['queue_full']);
    expect(client.start().finish()).toBe(false);
  });
  it('continues after transport failures and a throwing diagnostic callback', async () => {
    const send = vi.fn().mockRejectedValueOnce(new Error('SECRET')).mockResolvedValue(undefined);
    const { client } = capture({
      transport: { send },
      onDiagnostic: () => {
        throw new Error('callback');
      },
    });
    client.start().finish();
    client.start().finish();
    expect(await client.flush()).toBe(true);
    expect(client.stats()).toMatchObject({
      accepted: 2,
      sent: 1,
      failed: 1,
      dropped: 0,
      pending: 0,
    });
    await client.close();
  });
  it('snapshots events and bounds both the event count and total report size', async () => {
    const { client, records } = capture({ maxEvents: 2, maxReportBytes: 4096 });
    const span = client.start();
    const event = { status: 'READY' };
    span.event('pms', event);
    event.status = 'MUTATED';
    span.event('pms', { value: 'x'.repeat(2000) });
    span.event('pms', { status: 'FAILED' });
    span.event('pms', { status: 'OTHER' });
    span.finish();
    await client.flush();
    const report = decode(records[0]);
    expect(report.audits[0].events[0]).toEqual({ status: 'READY' });
    expect(report.audits.at(-1)?.type).toBe('sdk.truncated');
    expect(records[0].value.byteLength).toBeLessThanOrEqual(4096);
    expect(() => client.start().eventStrict('bad', { number: NaN })).toThrow();
    expect(
      () =>
        new AuditClient({
          domain: 'pms',
          env: 'test',
          appId: 'test',
          transport: { async send() {} },
          maxQueueSize: Infinity,
        }),
    ).toThrow();
    await client.close();
  });
});

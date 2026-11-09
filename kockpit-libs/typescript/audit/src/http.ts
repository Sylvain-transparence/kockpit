import type { AuditSpan } from './index';

export interface FilteredHttpExchange {
  startTimeMs: number;
  endTimeMs: number;
  method: string;
  /** A filtered path or route template, without query string or credentials. */
  path: string;
  status: number;
  requestBody?: string | null;
  responseBody?: string | null;
}

export function recordIncomingHttp(span: AuditSpan, exchange: FilteredHttpExchange): boolean {
  return span.tryEvent('builtin.web', {
    startTime: exchange.startTimeMs,
    endTime: exchange.endTimeMs,
    traceId: span.requestId,
    filtered: true,
    httpAuditedRequest: {
      method: exchange.method,
      uri: exchange.path,
      params: {},
      body: exchange.requestBody ?? null,
    },
    httpAuditedResponse: {
      status: exchange.status,
      body: exchange.responseBody ?? null,
    },
  });
}

export function recordOutgoingHttp(span: AuditSpan, exchange: FilteredHttpExchange): boolean {
  return span.tryEvent('builtin.httpexchanges', {
    startTime: exchange.startTimeMs,
    endTime: exchange.endTimeMs,
    filtered: true,
    httpAuditedRequest: {
      method: exchange.method,
      uri: exchange.path,
      params: {},
      body: exchange.requestBody ?? null,
    },
    httpAuditedResponse: {
      status: exchange.status,
      payload: exchange.responseBody ?? null,
    },
  });
}

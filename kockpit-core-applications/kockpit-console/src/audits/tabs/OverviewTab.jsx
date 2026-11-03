import React, { useMemo } from 'react';
import {
    Activity, ArrowLeftRight, ArrowRight, Box, Clock, GitBranch, Globe, Hash, Layers, Network, Package, Tag, Timer,
} from 'lucide-react';
import CopyButton from '../../components/CopyButton.jsx';
import { MethodChip, StatusChip } from './exchange/HttpChips.jsx';
import { parseExchanges } from './exchange/exchangeModel.js';
import { FailedIcon } from './rules/RulesCommon.jsx';
import { FAILED_BORDER } from './rules/failedStyle.js';
import { count, parseExecutions, ruleFailed, isError } from './rules/rulesModel.js';

// The audit at a glance, in the Rules tab's style: a dark banner with what was called and how it
// went, a strip of what the audit holds (each entry opening its tab), then the cards about where
// it ran, when, and how to find it again.

const indexed = (request, key) => request?.indexedKeyValues?.find(kv => kv.key === key)?.value;

const formatInstant = (value) => {
    if (value == null) return null;
    const date = new Date(value);
    if (isNaN(date.getTime())) return String(value);
    return `${date.toLocaleString('en-GB', {
        year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
    })}.${String(date.getMilliseconds()).padStart(3, '0')}`;
};

const formatDay = (value) =>
    new Date(value).toLocaleDateString('en-GB', { year: 'numeric', month: 'short', day: '2-digit' });

const formatDuration = (ms) => {
    if (ms == null || isNaN(ms)) return null;
    if (ms < 1000) return `${Math.round(ms)} ms`;
    return `${(ms / 1000).toFixed(ms < 10000 ? 2 : 1)} s`;
};

// The call this audit is about: the recorded web exchange when there is one, the indexed
// requestUri/httpMethod/httpStatus otherwise (e.g. dark canary replays, which record no web exchange)
const summarizeCall = (request) => {
    let exchange = null;
    try {
        exchange = parseExchanges(request)[0] || null;
    } catch {
        // unreadable web audit: the indexed values below still tell the story
    }
    const traceId = indexed(request, 'traceId') || exchange?.traceId;
    const uri = exchange?.request.uri || indexed(request, 'requestUri') || indexed(request, 'httpPath');
    const method = exchange?.request.method || indexed(request, 'httpMethod');
    const status = exchange?.response?.status ?? indexed(request, 'httpStatus');
    const http = uri || method || status != null;
    return { http, uri, method, status, traceId, principal: exchange?.request.principal || indexed(request, 'principal') };
};

const Banner = ({ request, call, duration }) => (
    <div className="rounded-xl bg-slate-800 px-4 py-3 text-white shadow-sm">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <Activity className="w-4 h-4 shrink-0 text-sky-300" />
            {call.http ? (
                <>
                    <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wider text-slate-400">Request</span>
                    <MethodChip method={call.method} />
                    <span className="flex items-center min-w-0 flex-1">
                        <span className="truncate font-mono text-sm font-semibold" title={call.uri}>{call.uri || '/'}</span>
                        {call.uri && <span className="[&_button:hover]:bg-white/10 [&_svg]:text-slate-300"><CopyButton value={call.uri} /></span>}
                    </span>
                    {call.status != null && <StatusChip status={call.status} />}
                </>
            ) : (
                <>
                    <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wider text-slate-400">Audit</span>
                    <span className="flex-1 min-w-0 truncate text-sm font-semibold">{request.appId || 'Unknown application'} <span className="font-normal text-slate-400">· no HTTP request recorded</span></span>
                </>
            )}
            {duration != null && (
                <span className="shrink-0 inline-flex items-center gap-1 text-xs font-semibold text-slate-200 tabular-nums">
                    <Timer className="w-3.5 h-3.5 text-slate-400" />{formatDuration(duration)}
                </span>
            )}
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-slate-300">
            <span className="inline-flex items-center gap-1.5"><Package className="w-3.5 h-3.5 text-slate-400" />{request.appId || '—'}</span>
            <span className="inline-flex items-center gap-1.5"><Globe className="w-3.5 h-3.5 text-slate-400" />{request.domain || '—'} / {request.env || '—'}</span>
            {call.principal && <span className="text-slate-400">as <span className="text-slate-200">{call.principal}</span></span>}
            {request.start != null && <span className="inline-flex items-center gap-1.5"><Clock className="w-3.5 h-3.5 text-slate-400" />{formatInstant(request.start)}</span>}
        </div>
    </div>
);

// One entry per kind of data the audit holds; those with a tab open it
const describeContents = (request) => {
    const entries = [];
    const audits = request.audits || [];
    const types = audits.map(audit => audit.type);

    if (types.includes('builtin.web')) {
        entries.push({ key: 'web', tab: 'request', icon: <ArrowLeftRight className="w-4 h-4" />, title: 'Request & Response', detail: 'web exchange recorded' });
    }
    const exchanges = audits.filter(audit => audit.type === 'builtin.httpexchanges').reduce((n, audit) => n + (audit.events?.length || 0), 0);
    if (types.includes('builtin.httpexchanges')) {
        entries.push({ key: 'httpexchanges', tab: 'httpexchange', icon: <Network className="w-4 h-4" />, title: 'HTTP Exchange', detail: count(exchanges, 'outgoing call') });
    }
    if (types.includes('kengine.flows')) {
        let executions = [];
        try {
            executions = parseExecutions(request);
        } catch {
            // the Rules tab says why
        }
        const rules = executions.flatMap(e => e.rules);
        const failed = rules.filter(ruleFailed).length + executions.filter(e => isError(e.error)).length;
        entries.push({
            key: 'rules', tab: 'rules', icon: <GitBranch className="w-4 h-4" />, title: 'Rules',
            detail: `${count(executions.length, 'flow')} · ${count(rules.length, 'rule')}`,
            failed: failed > 0 ? `${failed} in error` : null,
        });
    }
    const keyValues = request.indexedKeyValues?.length || 0;
    if (keyValues > 0) {
        entries.push({ key: 'kv', tab: 'keyvalue', icon: <Tag className="w-4 h-4" />, title: 'Key & Value', detail: count(keyValues, 'indexed value') });
    }
    // anything else (e.g. a custom audit type) has no tab of its own: named, with the Json tab to read it
    types.filter(type => !['builtin.web', 'builtin.httpexchanges', 'kengine.flows'].includes(type))
        .filter((type, index, all) => all.indexOf(type) === index)
        .forEach(type => entries.push({ key: type, tab: 'json', icon: <Box className="w-4 h-4" />, title: type, detail: 'in the Json tab', mono: true }));
    return entries;
};

const Contents = ({ entries, onOpenTab }) => (
    <section>
        <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-gray-500">
            <Layers className="w-3.5 h-3.5" />In this audit
        </h4>
        {entries.length === 0 ? (
            <p className="mt-1.5 text-sm italic text-gray-400">Nothing but the audit header</p>
        ) : (
            <div className="mt-2 flex flex-wrap gap-2">
                {entries.map(entry => (
                    <button
                        key={entry.key}
                        type="button"
                        onClick={() => onOpenTab?.(entry.tab)}
                        className={`group inline-flex items-center gap-2 rounded-lg border-2 bg-white px-3 py-2 text-left shadow-sm transition-shadow hover:shadow-md focus:outline-none focus:ring-2 focus:ring-blue-400 ${entry.failed ? `${FAILED_BORDER} bg-red-50` : 'border-slate-200'}`}
                    >
                        <span className={entry.failed ? 'text-red-700' : 'text-slate-600'}>{entry.icon}</span>
                        <span className="min-w-0">
                            <span className={`block text-sm font-semibold text-gray-900 ${entry.mono ? 'font-mono' : ''}`}>{entry.title}</span>
                            <span className="flex items-center gap-1 text-xs text-gray-500">
                                {entry.detail}
                                {entry.failed && <><span className="text-gray-300">·</span><FailedIcon className="w-3 h-3" /><span className="font-semibold text-red-700">{entry.failed}</span></>}
                            </span>
                        </span>
                        <ArrowRight className="w-4 h-4 shrink-0 text-gray-300 transition-transform group-hover:translate-x-0.5 group-hover:text-gray-500" />
                    </button>
                ))}
            </div>
        )}
    </section>
);

const Card = ({ icon, title, children }) => (
    <section className="min-w-0 rounded-xl border-2 border-slate-200 bg-white shadow-sm">
        <div className="flex items-center gap-2 px-4 py-2 rounded-t-[10px] bg-slate-100 text-slate-900">
            {icon}
            <h4 className="text-xs font-bold uppercase tracking-wider">{title}</h4>
        </div>
        <dl className="divide-y divide-gray-100">{children}</dl>
    </section>
);

const Row = ({ label, value, mono, copy }) => (
    <div className="grid grid-cols-[8rem_minmax(0,1fr)] items-center gap-3 px-4 py-2 text-sm">
        <dt className="text-gray-500">{label}</dt>
        <dd className="flex items-center justify-end gap-1 min-w-0 text-right">
            {value == null || value === ''
                ? <span className="text-gray-300">—</span>
                : <span className={`min-w-0 break-all font-medium text-gray-900 ${mono ? 'font-mono text-xs' : ''}`}>{value}</span>}
            {copy && value ? <CopyButton value={String(value)} /> : null}
        </dd>
    </div>
);

const OverviewTab = ({ request, onOpenTab }) => {
    const call = useMemo(() => summarizeCall(request), [request]);
    const contents = useMemo(() => describeContents(request), [request]);

    const duration = request?.end != null && request?.start != null ? request.end - request.start : null;
    const expires = request?.start != null && request?.ttl ? request.start + request.ttl * 24 * 3600 * 1000 : null;

    return (
        <div className="space-y-5">
            <Banner request={request} call={call} duration={duration} />
            <Contents entries={contents} onOpenTab={onOpenTab} />
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
                <Card icon={<Hash className="w-4 h-4" />} title="Identifiers">
                    <Row label="Audit ID" value={request?.id} mono copy />
                    {request?.requestId !== request?.id && <Row label="Request ID" value={request?.requestId} mono copy />}
                    <Row label="Trace ID" value={call.traceId} mono copy />
                    {request?.s3Key && <Row label="Archive" value={request.s3Key} mono copy />}
                </Card>
                <Card icon={<Clock className="w-4 h-4" />} title="Timing">
                    <Row label="Started" value={formatInstant(request?.start)} />
                    <Row label="Ended" value={formatInstant(request?.end)} />
                    <Row label="Duration" value={formatDuration(duration)} />
                    <Row label="Kept" value={request?.ttl ? `${count(request.ttl, 'day')}${expires ? ` · until ${formatDay(expires)}` : ''}` : null} />
                </Card>
                <Card icon={<Package className="w-4 h-4" />} title="Application">
                    <Row label="App ID" value={request?.appId} mono />
                    <Row label="Version" value={request?.version} mono />
                    <Row label="Artifact" value={request?.artifact} mono />
                </Card>
                <Card icon={<Globe className="w-4 h-4" />} title="Environment">
                    <Row label="Domain" value={request?.domain} mono />
                    <Row label="Environment" value={request?.env} mono />
                    <Row label="Hostname" value={request?.hostname} mono copy />
                </Card>
            </div>
        </div>
    );
};

export default OverviewTab;

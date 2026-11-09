import React, { useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, ChevronsDown, TriangleAlert, Workflow, X } from 'lucide-react';
import { FailedIcon, StatusBadge, TimelineBar } from './RulesCommon.jsx';
import { FAILED_BORDER, hatch } from './failedStyle.js';
import RuleCard from './RuleCard.jsx';
import { count, isError, isWarning, ruleFailed, ruleWarning, shortName, stepName } from './rulesModel.js';

// Zoomed-out overview, to take in a large flow at a glance: one lane per execution, its rules
// as tiles in execution order - wider and darker the longer they took; failed: red, dashed,
// hatched and with an X icon - each tile carrying a mark per step (diamond: predicate, square:
// action, X: failed).
// Clicking selects, like the other views, so switching to Flow or Timeline lands on it; the
// selected rule is drawn out in a card under the lanes (see RuleCard).

// Beyond this many steps, a tile shows the first ones and "+N"
const MAX_STEP_MARKS = 40;

// Duration heat, from the fastest to the slowest rule of the request
const HEAT = [
    'bg-sky-50 border-sky-200 text-sky-950',
    'bg-sky-100 border-sky-300 text-sky-950',
    'bg-sky-200 border-sky-400 text-sky-950',
    'bg-sky-300 border-sky-500 text-sky-950',
    'bg-sky-500 border-sky-600 text-white',
];
const FAILED_TILE = `bg-red-100 ${FAILED_BORDER} text-red-950`;
const WARNING_TILE = 'bg-amber-100 border-amber-400 text-amber-950';
const displayStatus = (status, failed) => failed && !isError(status) ? 'ERROR' : status;
const mapStatus = (rule) => ruleFailed(rule) ? 'failed' : ruleWarning(rule) ? 'WARNING' : null;

const heatOf = (time, maxTime) => {
    if (!time || maxTime <= 0) return HEAT[0];
    return HEAT[Math.min(HEAT.length - 1, Math.floor((time / maxTime) * HEAT.length))];
};

// Divider between the map and the rule card: says what the card shows (execution › rule, its
// position) and places the rule among its execution's rules - a strip of segments as wide as
// each rule took and colored like its tile - with previous/next to walk through them.
const FocusBar = ({ execution, rule, maxTime, onSelect }) => {
    const index = execution.rules.findIndex(r => r.key === rule.key);
    const previous = execution.rules[index - 1];
    const next = execution.rules[index + 1];
    const navButton = 'shrink-0 p-1 rounded text-slate-300 hover:text-white hover:bg-white/10 disabled:opacity-30 disabled:hover:bg-transparent focus:outline-none focus:ring-2 focus:ring-sky-300';

    return (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg bg-slate-800 px-3 py-2 text-white shadow-sm" aria-label="Shown below">
            <div className="flex items-center gap-2 min-w-0 text-xs">
                <ChevronsDown className="w-4 h-4 shrink-0 text-sky-300" aria-hidden="true" />
                <span className="shrink-0 uppercase tracking-wider text-slate-400">Rule details</span>
                <span className="truncate" title={`${execution.name || 'Unnamed execution'} › ${rule.name}`}>
                    <span className="text-slate-300">{execution.name || 'Execution'}</span>
                    <span className="mx-1 text-slate-500">›</span>
                    <span className="font-semibold">{shortName(rule.name)}</span>
                    {ruleFailed(rule) && <span className="ml-1.5 font-semibold text-red-300">✕ failed</span>}
                    {mapStatus(rule) === 'WARNING' && <span className="ml-1.5 font-semibold text-amber-300">WARNING</span>}
                </span>
                <span className="shrink-0 text-slate-400 tabular-nums">{index + 1} / {execution.rules.length}</span>
            </div>
            <div className="flex flex-1 items-center gap-1 min-w-[12rem]">
                <button type="button" className={navButton} disabled={!previous} onClick={() => onSelect(previous.key)}
                        aria-label="Previous rule" title={previous ? `Previous: ${previous.name}` : undefined}>
                    <ChevronLeft className="w-4 h-4" />
                </button>
                <div className="flex flex-1 h-3 gap-px rounded-sm" role="group" aria-label={`Rules of ${execution.name || 'the execution'}`}>
                    {execution.rules.map(r => (
                        <button
                            key={r.key}
                            type="button"
                            onClick={() => onSelect(r.key)}
                            title={`${r.name}${r.time != null ? ` · ${r.time} ms` : ''}${mapStatus(r) ? ` · ${mapStatus(r)}` : ''}`}
                            aria-label={`${shortName(r.name)}${mapStatus(r) ? ` (${mapStatus(r)})` : ''}`}
                            aria-current={r.key === rule.key ? 'true' : undefined}
                            style={{ flex: `${Math.max(r.time || 0, 1)} 1 0.25rem`, ...(ruleFailed(r) ? hatch(true) : {}) }}
                            className={`min-w-[3px] rounded-[2px] focus:outline-none focus:ring-2 focus:ring-sky-300 ${ruleFailed(r) ? 'bg-red-600' : ruleWarning(r) ? 'bg-amber-400' : heatOf(r.time, maxTime)} ${r.key === rule.key
                                ? 'relative z-10 outline outline-2 outline-offset-1 outline-white'
                                : 'opacity-80 hover:opacity-100'}`}
                        />
                    ))}
                </div>
                <button type="button" className={navButton} disabled={!next} onClick={() => onSelect(next.key)}
                        aria-label="Next rule" title={next ? `Next: ${next.name}` : undefined}>
                    <ChevronRight className="w-4 h-4" />
                </button>
            </div>
        </div>
    );
};

const StepMark = ({ step, selected, onSelect }) => {
    const isPredicate = step.actionPredicate === 'PREDICATE';
    const failed = isError(step.error);
    const warning = isWarning(step.error);
    const color = isPredicate ? 'bg-amber-300 border-amber-500' : 'bg-purple-300 border-purple-500';
    // a failed step is an X, whatever its kind: a shape, not just a color
    const shape = failed
        ? 'w-3 h-3 rounded-sm flex items-center justify-center bg-red-700 border-red-900 text-white'
        : warning ? 'w-3 h-3 rounded-sm flex items-center justify-center bg-amber-100 border-amber-500 text-amber-950'
            : `w-2.5 h-2.5 ${isPredicate ? 'rotate-45 rounded-[1px]' : 'rounded-sm'} ${color}`;
    return (
        <button
            type="button"
            onClick={(e) => { e.stopPropagation(); onSelect(step.key); }}
            title={`${stepName(step)}${step.time != null ? ` · ${step.time} ms` : ''}${failed ? ` · ${step.errorMessage || step.error}` : warning ? ' · WARNING' : ''}`}
            aria-label={`${shortName(stepName(step))}${warning ? ' (WARNING)' : ''}`}
            aria-pressed={selected}
            className={`border focus:outline-none focus:ring-2 focus:ring-blue-500 ${shape} ${selected ? 'ring-2 ring-blue-600 ring-offset-1' : ''}`}
        >
            {failed && <X className="w-2.5 h-2.5" strokeWidth={4} />}
            {warning && <TriangleAlert className="w-2.5 h-2.5" aria-hidden="true" />}
        </button>
    );
};

const RuleTile = ({ rule, maxTime, selected, onSelect }) => {
    const failed = ruleFailed(rule);
    const warning = mapStatus(rule) === 'WARNING';
    const marks = rule.steps.slice(0, MAX_STEP_MARKS);
    const hidden = rule.steps.length - marks.length;
    const isSelected = selected === rule.key;
    const containsSelected = rule.steps.some(step => step.key === selected);

    return (
        <div
            role="button"
            tabIndex={0}
            onClick={() => onSelect(rule.key)}
            onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onSelect(rule.key);
                }
            }}
            aria-pressed={isSelected}
            title={`${rule.name}${rule.time != null ? ` · ${rule.time} ms` : ''} · ${count(rule.steps.length, 'step')}${mapStatus(rule) ? ` · ${mapStatus(rule)}` : ''}`}
            // longer rules get wider tiles: the grow factor is the duration, on top of a minimum width
            style={{ flex: `${Math.max(rule.time || 0, 1)} 1 9.5rem`, ...(failed ? hatch(false) : {}) }}
            className={`min-w-0 max-w-full rounded-lg border-2 px-2.5 py-2 cursor-pointer transition-shadow hover:shadow-md focus:outline-none focus:ring-2 focus:ring-blue-400 ${failed ? FAILED_TILE : warning ? WARNING_TILE : heatOf(rule.time, maxTime)} ${isSelected ? 'ring-2 ring-blue-600 ring-offset-2' : containsSelected ? 'ring-1 ring-blue-400 ring-offset-1' : ''}`}
        >
            <div className="flex items-baseline justify-between gap-2">
                <span className="flex items-center gap-1 min-w-0">
                    {failed && <FailedIcon className="w-3.5 h-3.5" />}
                    <span className="text-xs font-semibold truncate">{shortName(rule.name)}</span>
                </span>
                {warning && <StatusBadge error="WARNING" small />}
                <span className="shrink-0 text-[10px] font-semibold tabular-nums opacity-80">{rule.time != null ? `${rule.time} ms` : ''}</span>
            </div>
            {rule.steps.length > 0 && (
                <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                    {marks.map(step => (
                        <StepMark key={step.key} step={step} selected={selected === step.key} onSelect={onSelect} />
                    ))}
                    {hidden > 0 && <span className="text-[10px] font-semibold opacity-70">+{hidden}</span>}
                </div>
            )}
        </div>
    );
};

const Lane = ({ execution, showHeader, maxTime, timeWindow, selected, onSelect }) => (
    <section className="rounded-xl border border-gray-200 bg-white">
        {/* dark and solid, as in the Flow view: the lane's flow reads apart from its rule tiles */}
        {showHeader && (
            <div className="grid grid-cols-[minmax(0,1fr)_11rem] gap-4 items-center px-3 py-2 rounded-t-xl bg-slate-800">
                <button
                    type="button"
                    onClick={() => onSelect(execution.key)}
                    aria-pressed={selected === execution.key}
                    className={`flex items-center gap-2 min-w-0 rounded text-left text-white focus:outline-none focus:ring-2 focus:ring-blue-400 ${selected === execution.key ? 'ring-2 ring-sky-300 ring-offset-2 ring-offset-slate-800' : ''}`}
                >
                    <Workflow className="w-4 h-4 shrink-0 text-sky-300" />
                    <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wider text-slate-400">Flow</span>
                    <span className="text-sm font-semibold truncate">{execution.name || 'Unnamed execution'}</span>
                    <StatusBadge error={displayStatus(execution.error, execution.rules.some(ruleFailed))} small />
                    <span className="text-xs text-slate-300 tabular-nums">
                        {count(execution.rules.length, 'rule')}{execution.time != null ? ` · ${execution.time} ms` : ''}
                    </span>
                </button>
                <TimelineBar node={execution} timeWindow={timeWindow} tone="bg-sky-300" className="h-2 bg-slate-600" />
            </div>
        )}
        {/* items-start: a tile with many step marks doesn't stretch the others on its line */}
        <div className="flex flex-wrap items-start gap-2 p-3">
            {execution.rules.length > 0
                ? execution.rules.map(rule => (
                    <RuleTile key={rule.key} rule={rule} maxTime={maxTime} selected={selected} onSelect={onSelect} />
                ))
                : <span className="text-sm text-gray-400 italic">No rules recorded</span>}
        </div>
    </section>
);

const Legend = ({ maxTime }) => (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-[11px] text-gray-500">
        <span className="inline-flex items-center gap-1.5">
            <span>0 ms</span>
            {HEAT.map(tone => <span key={tone} className={`w-4 h-3 rounded-sm border ${tone}`} />)}
            <span>{maxTime} ms</span>
        </span>
        <span className="inline-flex items-center gap-1.5">
            <span className={`w-5 h-3.5 rounded-sm border-2 ${FAILED_TILE}`} style={hatch(false)} />
            <FailedIcon className="w-3.5 h-3.5" />failed
        </span>
        <span className="inline-flex items-center gap-1.5 text-amber-900">
            <TriangleAlert className="w-3.5 h-3.5" aria-hidden="true" />WARNING
        </span>
        <span className="inline-flex items-center gap-1.5"><span className="w-2.5 h-2.5 rotate-45 rounded-[1px] border bg-amber-300 border-amber-500" />predicate</span>
        <span className="inline-flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm border bg-purple-300 border-purple-500" />action</span>
        <span className="inline-flex items-center gap-1.5">
            <span className="w-3 h-3 rounded-sm border flex items-center justify-center bg-red-700 border-red-900 text-white"><X className="w-2.5 h-2.5" strokeWidth={4} /></span>failed step
        </span>
    </div>
);

const MapView = ({ executions, timeWindow, nodes, selected, onSelect }) => {
    const maxTime = useMemo(
        () => Math.max(0, ...executions.flatMap(e => e.rules.map(rule => rule.time || 0))),
        [executions]);

    // the rule drawn out under the map: the selected one, or the one of the selected step,
    // until its card is closed - picking anything on the map opens it again
    const [closedRule, setClosedRule] = useState(null);
    // bumped on each pick on the map, so the card scrolls into view then - not when the tab
    // opens on its default selection, nor when picking steps inside the card itself
    const [reveal, setReveal] = useState(0);
    const node = nodes.get(selected);
    const cardRule = node?.kind === 'rule' || node?.kind === 'step' ? node.rule : null;
    const select = (key) => {
        setClosedRule(null);
        onSelect(key);
    };
    const pickOnMap = (key) => {
        select(key);
        setReveal(n => n + 1);
    };

    return (
        <div className="bg-white shadow-sm sm:rounded-lg border border-gray-200">
            <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 border-b border-gray-200 bg-gray-50">
                <span className="text-xs font-medium text-gray-500 uppercase tracking-wider">Map</span>
                <Legend maxTime={maxTime} />
            </div>
            <div className="p-4 space-y-3 bg-[radial-gradient(circle,_#eef0f3_1px,_transparent_1px)] [background-size:16px_16px]">
                {executions.map(execution => (
                    <Lane
                        key={execution.key}
                        execution={execution}
                        showHeader={executions.length > 1 || !!execution.name}
                        maxTime={maxTime}
                        timeWindow={timeWindow}
                        selected={selected}
                        onSelect={pickOnMap}
                    />
                ))}
                {cardRule && cardRule.key !== closedRule && (
                    <>
                        <FocusBar execution={node.execution} rule={cardRule} maxTime={maxTime} onSelect={select} />
                        <RuleCard
                            rule={cardRule}
                            status={displayStatus(cardRule.error, ruleFailed(cardRule))}
                            tone={ruleFailed(cardRule) ? FAILED_TILE : ruleWarning(cardRule) ? WARNING_TILE : heatOf(cardRule.time, maxTime)}
                            timeWindow={timeWindow}
                            selected={selected}
                            onSelect={select}
                            reveal={reveal}
                            onClose={() => setClosedRule(cardRule.key)}
                        />
                    </>
                )}
            </div>
        </div>
    );
};

export default MapView;

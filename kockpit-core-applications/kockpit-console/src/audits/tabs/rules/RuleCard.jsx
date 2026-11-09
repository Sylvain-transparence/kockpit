import React, { useEffect, useMemo, useRef } from 'react';
import { GitBranch, HelpCircle, X, XCircle, Zap } from 'lucide-react';
import { FailedIcon, StatusBadge } from './RulesCommon.jsx';
import { FAILED_BORDER, hatch } from './failedStyle.js';
import { count, isError, isWarning, ruleFailed, shortName, stepName } from './rulesModel.js';

// One rule drawn out: its steps as a horizontal chain (predicate outcomes on the arrows), then a
// waterfall placing each step within the rule's own time span - finer than the request-wide
// timeline. Shown under the Map for the selected rule (or the rule of the selected step).

// The rule's own time window, from its steps and its own date/time
const ruleWindow = (rule) => {
    let start = Infinity;
    let end = -Infinity;
    [rule, ...rule.steps].forEach(node => {
        if (node.date == null) return;
        start = Math.min(start, node.date);
        end = Math.max(end, node.date + (node.time || 0));
    });
    return start === Infinity ? null : { start, span: Math.max(end - start, 1) };
};

const HorizontalArrow = ({ outcome, failed }) => {
    const stroke = failed ? '#f87171' : '#94a3b8';
    return (
        <div className="relative shrink-0 flex flex-col items-center justify-center w-14 self-center" aria-hidden="true">
            {outcome != null && (
                <span className={`absolute -top-5 text-[10px] font-mono font-semibold px-1 rounded border ${outcome
                    ? 'bg-green-50 text-green-700 border-green-200'
                    : 'bg-orange-50 text-orange-700 border-orange-200'}`}>
                    {String(outcome)}
                </span>
            )}
            <svg width="56" height="12" viewBox="0 0 56 12" className="overflow-visible">
                <line x1="0" y1="6" x2="48" y2="6" stroke={stroke} strokeWidth="2" strokeDasharray="4 4" className="flow-dash" />
                <path d="M46 1 L54 6 L46 11" fill="none" stroke={stroke} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
        </div>
    );
};

const ChainNode = ({ step, index, selected, onSelect }) => {
    const ref = useRef(null);
    const isPredicate = step.actionPredicate === 'PREDICATE';
    const failed = isError(step.error);
    const name = stepName(step);

    // keep the selected step visible in the (horizontally scrolling) chain
    useEffect(() => {
        if (selected) ref.current?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
    }, [selected]);

    return (
        <button
            ref={ref}
            type="button"
            onClick={() => onSelect(step.key)}
            title={name}
            aria-pressed={selected}
            className={`shrink-0 w-44 flex flex-col items-center gap-1.5 px-3 py-2.5 rounded-xl border-2 bg-white text-center shadow-sm transition-shadow hover:shadow-md focus:outline-none focus:ring-2 focus:ring-blue-400 ${failed
                ? `${FAILED_BORDER} bg-red-50`
                : isPredicate ? 'border-amber-200' : 'border-purple-200'} ${selected ? 'ring-2 ring-blue-600 ring-offset-2' : ''}`}
        >
            {isPredicate ? (
                <span className={`w-6 h-6 rotate-45 rounded-[4px] border-2 flex items-center justify-center ${failed ? 'bg-red-100 border-red-300' : 'bg-amber-100 border-amber-300'}`}>
                    <HelpCircle className={`w-3.5 h-3.5 -rotate-45 ${failed ? 'text-red-700' : 'text-amber-700'}`} />
                </span>
            ) : (
                <span className={`w-7 h-7 rounded-lg flex items-center justify-center ${failed ? 'bg-red-100 text-red-700' : 'bg-purple-100 text-purple-700'}`}>
                    <Zap className="w-4 h-4" />
                </span>
            )}
            <span className="text-[10px] font-semibold uppercase tracking-wider text-gray-400">#{index + 1} · {step.actionPredicate || 'ACTION'}</span>
            <span className="w-full text-xs font-semibold text-gray-900 truncate">{shortName(name)}</span>
            <span className="text-[11px] font-semibold text-gray-500 tabular-nums">{step.time != null ? `${step.time} ms` : ''}</span>
            {isWarning(step.error) && <StatusBadge error={step.error} small />}
            {failed && (
                <span className="w-full flex items-center justify-center gap-1 text-[11px] text-red-700 truncate">
                    <XCircle className="w-3 h-3 shrink-0" />
                    <span className="truncate">{step.errorMessage || step.error}</span>
                </span>
            )}
        </button>
    );
};

const WATERFALL_GRID = 'grid grid-cols-[minmax(0,14rem)_minmax(0,1fr)_4rem] gap-3 items-center';

const WaterfallRow = ({ step, index, stepsWindow, selected, onSelect }) => {
    const ref = useRef(null);
    const failed = isError(step.error);
    const isPredicate = step.actionPredicate === 'PREDICATE';
    const offset = stepsWindow && step.date != null ? step.date - stepsWindow.start : null;
    const left = offset != null ? (offset / stepsWindow.span) * 100 : 0;
    const width = Math.max(((step.time || 0) / (stepsWindow?.span || 1)) * 100, 0.75);

    // keep the selected step visible in the (vertically scrolling) waterfall
    useEffect(() => {
        if (selected) ref.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }, [selected]);

    return (
        <button
            ref={ref}
            type="button"
            onClick={() => onSelect(step.key)}
            aria-pressed={selected}
            className={`w-full ${WATERFALL_GRID} px-2 py-1 rounded text-left focus:outline-none focus:ring-2 focus:ring-blue-400 ${selected ? 'bg-blue-50' : 'hover:bg-gray-50'}`}
        >
            <span className="flex items-center gap-1.5 min-w-0 text-xs text-gray-800">
                <span className="shrink-0 text-gray-400 tabular-nums">#{index + 1}</span>
                {failed && <FailedIcon className="w-3.5 h-3.5" />}
                <span className="truncate" title={stepName(step)}>{shortName(stepName(step))}</span>
                {isWarning(step.error) && <StatusBadge error={step.error} small />}
            </span>
            <span className="relative h-2.5 rounded-full bg-gray-100" title={offset != null ? `+${offset}ms → ${offset + (step.time || 0)}ms` : ''}>
                {offset != null && (
                    <span
                        className={`absolute inset-y-0 rounded-full ${failed ? 'bg-red-600' : isPredicate ? 'bg-amber-400' : 'bg-purple-400'}`}
                        style={{ left: `${Math.min(left, 100 - width)}%`, width: `${width}%`, ...(failed ? hatch(true) : {}) }}
                    />
                )}
            </span>
            <span className="text-right text-xs font-semibold text-gray-600 tabular-nums">{step.time != null ? `${step.time} ms` : 'N/A'}</span>
        </button>
    );
};

// About 20 rows high, then it scrolls - a rule with many steps keeps the card a reasonable size
const Waterfall = ({ rule, stepsWindow, selected, onSelect }) => (
    <div className="max-h-[30rem] overflow-y-auto">
        <div className={`${WATERFALL_GRID} sticky top-0 z-10 bg-white px-2 pb-1 text-[10px] font-medium uppercase tracking-wider text-gray-400`}>
            <span>Step</span>
            <span className="flex justify-between normal-case tabular-nums"><span>0 ms</span><span>{stepsWindow ? `${stepsWindow.span} ms` : ''}</span></span>
            <span className="text-right">Duration</span>
        </div>
        <div className="space-y-0.5">
            {rule.steps.map((step, index) => (
                <WaterfallRow key={step.key} step={step} index={index} stepsWindow={stepsWindow} selected={selected === step.key} onSelect={onSelect} />
            ))}
        </div>
    </div>
);

const RuleCard = ({ rule, status = rule.error, tone, timeWindow, selected, onSelect, reveal, onClose }) => {
    const ref = useRef(null);
    const stepsWindow = useMemo(() => ruleWindow(rule), [rule]);

    // bring the card into view on each pick on the map (reveal is bumped then)
    useEffect(() => {
        if (reveal > 0) ref.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }, [reveal]);

    const offset = timeWindow && rule.date != null ? rule.date - timeWindow.start : null;
    const failed = ruleFailed(rule);

    return (
        <section ref={ref} className={`rounded-xl border-2 bg-white shadow-sm scroll-mt-20 ${failed ? FAILED_BORDER : 'border-gray-200'}`} aria-label={`Rule ${shortName(rule.name)}`}>
            <div className={`flex flex-wrap items-center gap-2 px-4 py-2.5 rounded-t-[10px] border-b ${tone}`}>
                <button
                    type="button"
                    onClick={() => onSelect(rule.key)}
                    className="flex items-center gap-2 min-w-0 rounded focus:outline-none focus:ring-2 focus:ring-blue-400"
                    title={rule.name}
                >
                    {failed ? <FailedIcon /> : <GitBranch className="w-4 h-4 shrink-0 opacity-70" />}
                    <span className="text-sm font-semibold truncate">{shortName(rule.name)}</span>
                </button>
                <StatusBadge error={status} small />
                <span className="text-xs opacity-80 tabular-nums">
                    {count(rule.steps.length, 'step')}
                    {rule.time != null ? ` · ${rule.time} ms` : ''}
                    {offset != null ? ` · starts at +${offset} ms` : ''}
                </span>
                <button
                    type="button"
                    onClick={onClose}
                    className="ml-auto p-1 rounded hover:bg-white/60 focus:outline-none focus:ring-2 focus:ring-blue-400"
                    aria-label="Close the rule card"
                    title="Close"
                >
                    <X className="w-4 h-4" />
                </button>
            </div>

            {rule.steps.length === 0 ? (
                <p className="px-4 py-6 text-sm text-gray-400 italic">No steps recorded for this rule.</p>
            ) : (
                <div className="divide-y divide-gray-100">
                    <div className="overflow-x-auto px-4 pt-8 pb-4">
                        <div className="flex items-stretch w-max">
                            {rule.steps.map((step, index) => {
                                const previous = rule.steps[index - 1];
                                return (
                                    <React.Fragment key={step.key}>
                                        {index > 0 && (
                                            <HorizontalArrow
                                                outcome={previous.actionPredicate === 'PREDICATE' ? previous.condition : null}
                                                failed={isError(previous.error)}
                                            />
                                        )}
                                        <ChainNode step={step} index={index} selected={selected === step.key} onSelect={onSelect} />
                                    </React.Fragment>
                                );
                            })}
                        </div>
                    </div>
                    <div className="px-4 py-3">
                        <Waterfall rule={rule} stepsWindow={stepsWindow} selected={selected} onSelect={onSelect} />
                    </div>
                </div>
            )}
        </section>
    );
};

export default RuleCard;

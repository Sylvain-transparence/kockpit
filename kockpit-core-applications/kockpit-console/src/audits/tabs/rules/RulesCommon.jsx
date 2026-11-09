import React from 'react';
import { AlertCircle, CheckCircle, XCircle, Timer, Zap, HelpCircle, TriangleAlert } from 'lucide-react';
import CopyButton from "../../../components/CopyButton.jsx";
import { VALID, isError, isWarning } from './rulesModel.js';
import { hatch } from './failedStyle.js';

// Building blocks shared by the Rules tab views and its details panel

// Failed rules and steps: see failedStyle.js for the dashed border and hatching
export const FailedIcon = ({ className = 'w-4 h-4' }) => (
    <XCircle className={`shrink-0 text-red-700 ${className}`} role="img" aria-label="Failed" />
);

const getDurationColor = (duration) => {
    if (duration == null) return 'text-gray-600 bg-gray-50 border-gray-200';
    if (duration < 10) return 'text-green-600 bg-green-50 border-green-200';
    if (duration < 50) return 'text-blue-600 bg-blue-50 border-blue-200';
    if (duration < 100) return 'text-orange-600 bg-orange-50 border-orange-200';
    return 'text-red-600 bg-red-50 border-red-200';
};

export const StatusBadge = ({ error, small }) => {
    const failed = isError(error);
    const warning = isWarning(error);
    const Icon = failed ? XCircle : warning ? TriangleAlert : CheckCircle;
    return (
        <span className={`inline-flex items-center rounded-full font-bold border ${small ? 'px-2 py-0.5 text-[10px]' : 'px-3 py-1 text-xs border-2'} ${failed
            ? 'bg-red-100 text-red-800 border-red-300'
            : warning ? 'bg-amber-100 text-amber-900 border-amber-300'
                : 'bg-green-100 text-green-800 border-green-300'}`}>
            <Icon className="w-3 h-3 mr-1" aria-hidden="true" />
            {error || VALID}
        </span>
    );
};

export const Duration = ({ value }) => value != null ? (
    <div className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-lg border ${getDurationColor(value)}`}>
        <Timer className="w-3.5 h-3.5" />
        <span className="text-xs font-bold">{value}ms</span>
    </div>
) : (
    <span className="text-xs text-gray-400">N/A</span>
);

export const ErrorBlock = ({ message, details }) => {
    if (!message && !details) return null;
    const detailsText = typeof details === 'string' ? details : JSON.stringify(details, null, 2);
    return (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3">
            <div className="flex items-start gap-2">
                <AlertCircle className="w-4 h-4 text-red-600 mt-0.5 shrink-0" />
                <div className="min-w-0 flex-1">
                    {message && <p className="text-sm font-medium text-red-800 break-words">{message}</p>}
                    {details && (
                        <pre className="mt-1 text-xs text-red-700 whitespace-pre-wrap break-words max-h-40 overflow-auto">{detailsText}</pre>
                    )}
                </div>
            </div>
        </div>
    );
};

// For predicates, condition is the evaluated result; it is meaningless on actions
export const ConditionChip = ({ step }) => {
    if (step.actionPredicate !== 'PREDICATE' || step.condition == null) return null;
    return (
        <span className={`shrink-0 text-xs font-mono font-semibold px-2 py-0.5 rounded border ${step.condition
            ? 'bg-green-50 text-green-700 border-green-200'
            : 'bg-orange-50 text-orange-700 border-orange-200'}`}>
            → {String(step.condition)}
        </span>
    );
};

export const StepTypeChip = ({ step }) => {
    const isPredicate = step.actionPredicate === 'PREDICATE';
    return (
        <span className={`shrink-0 inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded ${isPredicate
            ? 'bg-amber-100 text-amber-800'
            : 'bg-purple-100 text-purple-800'}`}>
            {isPredicate ? <HelpCircle className="w-3 h-3" /> : <Zap className="w-3 h-3" />}
            {step.actionPredicate || 'ACTION'}
        </span>
    );
};

export const PanelHeader = ({ children, copyValue }) => (
    <div className="flex items-center justify-between px-4 py-2 bg-gray-50 border-b border-gray-200">
        <h4 className="flex items-center gap-2 text-xs font-semibold text-gray-700 uppercase tracking-wide">{children}</h4>
        {copyValue && <CopyButton value={copyValue} />}
    </div>
);

export const JsonSection = ({ title, icon, value }) => {
    if (value == null) return null;
    const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    return (
        <div className="bg-white rounded-lg border border-gray-200">
            <PanelHeader copyValue={text}>{icon}{title}</PanelHeader>
            <pre className="p-4 text-xs text-gray-800 whitespace-pre-wrap break-words max-h-64 overflow-auto">{text}</pre>
        </div>
    );
};

// A node's place in the time window: offset from the start and duration, as a bar
export const TimelineBar = ({ node, timeWindow, tone = 'bg-green-500', className = 'h-2 bg-gray-100' }) => {
    const { date, time, error } = node;
    if (!timeWindow || date == null) return null;

    const offset = date - timeWindow.start;
    const left = (offset / timeWindow.span) * 100;
    // Keep sub-millisecond work visible as a thin marker
    const width = Math.max(((time || 0) / timeWindow.span) * 100, 0.75);
    const failed = isError(error);

    return (
        <div className={`relative rounded-full ${className}`} title={`+${offset}ms → ${offset + (time || 0)}ms (${time ?? 0}ms)${failed ? ' · failed' : ''}`}>
            <div
                className={`absolute inset-y-0 rounded-full ${failed ? 'bg-red-600' : tone}`}
                style={{ left: `${Math.min(left, 100 - width)}%`, width: `${width}%`, ...(failed ? hatch(true) : {}) }}
            />
        </div>
    );
};

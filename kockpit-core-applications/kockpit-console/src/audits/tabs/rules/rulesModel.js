// Rules execution data, as every Rules tab view reads it: parsed once from the audit's
// "kengine.flows" events into executions > rules > steps, each with a stable key
// (e0, e0-r1, e0-r1-s2) so a selection survives switching views.

export const VALID = 'VALID';

// Above this many steps, rules start collapsed
export const AUTO_EXPAND_MAX_STEPS = 30;

// Steps carry error=null when fine, rules/executions carry error="VALID"
export const isWarning = (error) => error === 'WARNING';
export const isError = (error) => error != null && error !== VALID && !isWarning(error);

// "com.acme.HelloRule.HelloDefault" -> "HelloDefault"; plain names are kept as is
export const shortName = (name) => {
    if (!name || /\s/.test(name) || !name.includes('.')) return name;
    return name.substring(name.lastIndexOf('.') + 1);
};

export const stepName = (step) => step.name || step.detail || 'Unnamed step';

export const count = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

// a step as recorded in the audit, without the key added when parsing
export const withoutKey = (step) => {
    const { key: _key, ...recorded } = step;
    return recorded;
};

export const ruleFailed = (rule) => isError(rule.error) || rule.steps.some(step => isError(step.error));

export const ruleWarning = (rule) => isWarning(rule.error) || rule.steps.some(step => isWarning(step.error));

export const parseExecutions = (request) => {
    const flows = request.audits?.find(a => a.type === "kengine.flows");
    if (!flows?.events) return [];

    const events = Array.isArray(flows.events) ? flows.events : [flows.events];
    return events.map((event, index) => {
        const eventData = typeof event === "string" ? JSON.parse(event) : event;
        const execution = eventData.executionEDTDTO || {};
        const rulesMap = execution.rules || {};

        return {
            key: `e${index}`,
            name: eventData.executionName,
            uuid: execution.executionUUID,
            error: execution.error,
            errorMessage: execution.errorMessage,
            errorDetails: execution.errorDetails,
            date: execution.date ?? eventData.startTime,
            time: execution.time ?? (eventData.endTime != null && eventData.startTime != null ? eventData.endTime - eventData.startTime : null),
            registryId: execution.fullRegistryReferentialId || execution.registryId,
            referential: execution.referential,
            logs: execution.logs,
            rules: (execution.executionRules || []).map((rule, ruleIndex) => ({
                key: `e${index}-r${ruleIndex}`,
                name: rule.name,
                detail: rule.detail,
                error: rule.error,
                errorMessage: rule.errorMessage,
                errorDetails: rule.errorDetails,
                date: rule.date,
                time: rule.time,
                steps: (rulesMap[rule.name] || []).map((step, stepIndex) => ({
                    ...step,
                    key: `e${index}-r${ruleIndex}-s${stepIndex}`
                }))
            }))
        };
    });
};

// Executions in the order they started (the audit lists them in the order they were recorded);
// stable, so ties and undated executions keep their recorded order - undated ones last
export const byStartTime = (executions) =>
    [...executions].sort((a, b) => (a.date ?? Infinity) - (b.date ?? Infinity) || 0);

// Time window covering every execution, rule and step, used to place the timeline bars
export const computeTimeWindow = (executions) => {
    let start = Infinity;
    let end = -Infinity;
    const visit = (node) => {
        if (node.date == null) return;
        start = Math.min(start, node.date);
        end = Math.max(end, node.date + (node.time || 0));
    };
    executions.forEach(execution => {
        visit(execution);
        execution.rules.forEach(rule => {
            visit(rule);
            rule.steps.forEach(visit);
        });
    });
    if (start === Infinity) return null;
    return { start, span: Math.max(end - start, 1) };
};

// Everything selectable, by key
export const indexNodes = (executions) => {
    const nodes = new Map();
    executions.forEach(execution => {
        nodes.set(execution.key, { kind: 'execution', execution });
        execution.rules.forEach(rule => {
            nodes.set(rule.key, { kind: 'rule', rule, execution });
            rule.steps.forEach((step, index) => nodes.set(step.key, { kind: 'step', step, index, rule, execution }));
        });
    });
    return nodes;
};

// Rules starting collapsed: the ones with too many steps to show at once
export const initiallyCollapsedRules = (executions) => executions
    .flatMap(execution => execution.rules)
    .filter(rule => rule.steps.length > AUTO_EXPAND_MAX_STEPS)
    .map(rule => rule.key);

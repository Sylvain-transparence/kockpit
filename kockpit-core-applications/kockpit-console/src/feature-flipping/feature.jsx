import React, {useEffect, useState} from "react";
import {getFeatureFlags, syncFeatureFlags, updateFeatureFlag} from "../services/api.js";
import {AlertTriangle, CheckCircle2, Clock, Loader2, RefreshCw, Server, ToggleLeft, ToggleRight} from "lucide-react";
import {useSearchParams} from "react-router-dom";
import Stats from "../components/StatsComponent.jsx";

const StateButtonForFlag = ({flag, toggle}) => {
    const [enabled, setEnabled] = useState(flag.enabled);

    // Sync local state with prop changes
    useEffect(() => {
        setEnabled(flag.enabled);
    }, [flag.enabled]);

    function handleToggle() {
        toggle(flag);
    }

    return (
        <div className="flex items-center gap-2">
            <button
                onClick={handleToggle}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 ${
                    enabled ? "bg-green-500" : "bg-slate-300"
                }`}
                role="switch"
                aria-checked={enabled}
            >
                <span
                    className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                        enabled ? "translate-x-6" : "translate-x-1"
                    }`}
                />
            </button>
            <span className={`text-sm font-medium whitespace-nowrap ${enabled ? "text-green-700" : "text-slate-600"}`}>
                {enabled ? "Enabled" : "Disabled"}
            </span>
        </div>
    )
};

// Instance states sent by the backend. Each one differs by icon, label AND shape (solid / striped /
// dashed border), never by colour alone.
const STRIPES = {backgroundImage: "repeating-linear-gradient(135deg, rgba(180,83,9,.18) 0 4px, transparent 4px 8px)"};
const INSTANCE_STATUS = {
    IN_SYNC: {
        label: "In sync", Icon: CheckCircle2,
        badge: "bg-emerald-600 text-white border border-emerald-600",
        tile: "border-emerald-200 bg-emerald-50/40",
        segment: "bg-emerald-600",
    },
    OUTDATED: {
        label: "Out of date", Icon: AlertTriangle,
        badge: "bg-amber-50 text-amber-800 border-2 border-amber-500",
        tile: "border-amber-300 bg-amber-50/60",
        segment: "bg-amber-100 border border-amber-500", segmentStyle: STRIPES,
    },
    WAITING: {
        label: "Waiting", Icon: Loader2, spin: true,
        badge: "bg-white text-slate-600 border border-dashed border-slate-400",
        tile: "border-dashed border-slate-300 bg-white",
        segment: "bg-white border border-dashed border-slate-400",
    },
};
const ORDER = ["IN_SYNC", "OUTDATED", "WAITING"];

const timeAgo = (ms) => {
    if (!ms) return "";
    const seconds = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
    return new Date(ms).toLocaleString();
};

const StatusBadge = ({status}) => {
    const {Icon, label, badge, spin} = INSTANCE_STATUS[status] ?? INSTANCE_STATUS.WAITING;
    return (
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap ${badge}`}>
            <Icon className={`h-3.5 w-3.5 ${spin ? "animate-spin" : ""}`} aria-hidden="true" />
            {label}
        </span>
    );
};

const ValueChip = ({enabled}) => {
    const Icon = enabled ? ToggleRight : ToggleLeft;
    return (
        <span className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-1.5 py-0.5 text-xs font-mono font-semibold text-slate-700">
            <Icon className="h-3.5 w-3.5" aria-hidden="true" />
            {enabled ? "ON" : "OFF"}
        </span>
    );
};

const InstancesForFlag = ({instances, expected}) => {
    if (!instances) return null;
    if (instances.length === 0) {
        return (
            <div className="border-t border-slate-100 pt-3 flex items-center gap-2 text-xs text-slate-400 italic">
                <Server className="h-3.5 w-3.5" aria-hidden="true" /> No running instance reported
            </div>
        );
    }
    const sorted = [...instances].sort((a, b) =>
        ORDER.indexOf(b.status) - ORDER.indexOf(a.status) || a.instanceId.localeCompare(b.instanceId));
    const counts = Object.fromEntries(ORDER.map(s => [s, instances.filter(i => i.status === s).length]));
    const allInSync = counts.IN_SYNC === instances.length;
    return (
        <div className="border-t border-slate-100 pt-3 space-y-3">
            {/* Summary: one segment per instance + counters */}
            <div className="flex flex-wrap items-center gap-3">
                <span className="text-xs uppercase tracking-wide text-slate-500 font-semibold">
                    Instances {counts.IN_SYNC}/{instances.length} in sync
                </span>
                <div className="flex gap-1" aria-hidden="true">
                    {sorted.slice().reverse().map(instance => {
                        const status = INSTANCE_STATUS[instance.status] ?? INSTANCE_STATUS.WAITING;
                        return <span key={instance.instanceId} title={`${instance.instanceId}: ${status.label}`}
                                     className={`h-2.5 w-6 rounded-sm ${status.segment}`} style={status.segmentStyle} />;
                    })}
                </div>
                {allInSync ? (
                    <StatusBadge status="IN_SYNC" />
                ) : (
                    <div className="flex flex-wrap gap-1.5">
                        {ORDER.filter(s => counts[s] > 0).map(s => (
                            <span key={s} className="inline-flex items-center gap-1 text-xs text-slate-600">
                                <StatusBadge status={s} /> ×{counts[s]}
                            </span>
                        ))}
                    </div>
                )}
            </div>

            {/* One tile per instance, problems first */}
            <ul className="grid gap-2 sm:grid-cols-2">
                {sorted.map(instance => {
                    const status = INSTANCE_STATUS[instance.status] ?? INSTANCE_STATUS.WAITING;
                    return (
                        <li key={instance.instanceId}
                            className={`flex items-center justify-between gap-3 rounded-lg border px-3 py-2 ${status.tile}`}
                            style={instance.status === "OUTDATED" ? STRIPES : undefined}>
                            <div className="flex items-center gap-2 min-w-0">
                                <Server className="h-4 w-4 flex-shrink-0 text-slate-500" aria-hidden="true" />
                                <div className="min-w-0">
                                    <div className="font-mono text-xs text-slate-800 truncate" title={instance.instanceId}>
                                        {instance.instanceId}
                                    </div>
                                    <div className="flex items-center gap-1.5 text-xs text-slate-500 mt-0.5">
                                        {instance.status === "WAITING" ? (
                                            <span>no acknowledgement yet</span>
                                        ) : (
                                            <>
                                                <span>has</span> <ValueChip enabled={instance.enabled} />
                                                {instance.status === "OUTDATED" && (
                                                    <><span>expected</span> <ValueChip enabled={expected} /></>
                                                )}
                                                {instance.ackDate && (
                                                    <span className="inline-flex items-center gap-0.5" title={new Date(instance.ackDate).toLocaleString()}>
                                                        <Clock className="h-3 w-3" aria-hidden="true" /> {timeAgo(instance.ackDate)}
                                                    </span>
                                                )}
                                            </>
                                        )}
                                    </div>
                                </div>
                            </div>
                            <StatusBadge status={instance.status} />
                        </li>
                    );
                })}
            </ul>
        </div>
    );
};

// Backend fields left null (no comment stored, no value yet) must not hide the manifest's ones.
const withoutNulls = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== null && v !== undefined));

const FeatureFlippingPage = ({ domain, env, config }) => {
    const [searchParams] = useSearchParams();
    const serviceId = searchParams.get('service') || null;
    const [history, setHistory] = useState([]);
    const [activeTab, setActiveTab] = useState("properties");
    const [flags, setFlags] = useState([]);
    const [serviceName, setServiceName] = useState('Feature Flipping');
    const [syncing, setSyncing] = useState(false);

    useEffect(() => {
        if (!config['services'] || !serviceId) return;
        const service = config['services'].find(s => s.type === 'feature-flipping' && s.id === serviceId);
        if (!service) return;

        setServiceName(service.name || service.label || 'Feature Flipping');
        const manifestFlags = service.config?.keys ?? [];

        getFeatureFlags(domain, env, serviceId)
            .then(apiFlags => {
                const stateMap = Object.fromEntries(apiFlags.map(f => [f.key, f]));
                setFlags(manifestFlags.map(f => f.key in stateMap ? { ...f, ...withoutNulls(stateMap[f.key]) } : f));
            })
            .catch(() => setFlags([...manifestFlags]));
    }, [domain, env, config, serviceId]);

    // Refresh the instances' acknowledgements (they arrive with the applications' polling).
    useEffect(() => {
        if (!serviceId) return;
        const timer = setInterval(() => {
            getFeatureFlags(domain, env, serviceId)
                .then(apiFlags => {
                    const stateMap = Object.fromEntries(apiFlags.map(f => [f.key, f]));
                    setFlags(prev => prev.map(f => f.key in stateMap ? { ...f, instances: stateMap[f.key].instances } : f));
                })
                .catch(() => {});
        }, 15000);
        return () => clearInterval(timer);
    }, [domain, env, serviceId]);

    const handleToggle = (flag) => {
        // Create updated flag with toggled value
        const updatedFlag = { ...flag, enabled: !flag.enabled };

        // Optimistically update UI
        setFlags(flags.map(f => f.key === flag.key ? updatedFlag : f));

        updateFeatureFlag(domain, env, serviceId, updatedFlag)
            .then(resp => {
                console.log('Flag updated successfully:', resp);
            })
            .catch(err => {
                console.log(`Error updating ${err}`);
                // Revert on error
                setFlags(flags.map(f => f.key === flag.key ? flag : f));
            });
    }

    const totalKeys = flags.length;
    const modifiedKeys = 0; // TODO: track modifications

    const handleSync = () => {
        setSyncing(true);
        syncFeatureFlags(domain, env, serviceId)
            .then(() => getFeatureFlags(domain, env, serviceId))
            .then(apiFlags => {
                const stateMap = Object.fromEntries(apiFlags.map(f => [f.key, f]));
                setFlags(prev => prev.map(f => f.key in stateMap ? { ...f, ...withoutNulls(stateMap[f.key]) } : f));
            })
            .finally(() => setSyncing(false));
    };

    return (
        <div className="min-h-screen bg-slate-50">
            <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
                <div className="mb-8 flex items-start justify-between">
                    <div>
                        <h1 className="text-3xl font-bold text-slate-800 mb-2 flex items-center gap-3">
                            <ToggleRight className="h-8 w-8 text-blue-600" />
                            {serviceName}
                        </h1>
                        <p className="text-slate-600">Feature Flipping</p>
                    </div>
                    <button
                        onClick={handleSync}
                        disabled={syncing}
                        className="flex items-center gap-2 px-4 py-2 rounded-lg bg-blue-600 text-white hover:bg-blue-700 active:bg-blue-800 disabled:opacity-50 disabled:cursor-not-allowed transition-colors text-sm font-medium shadow-sm"
                        title="Force republish all flags to consumers"
                    >
                        <RefreshCw className={`h-4 w-4 ${syncing ? 'animate-spin' : ''}`} />
                        {syncing ? 'Syncing…' : 'Sync'}
                    </button>
                </div>

                {/* Stats Cards */}
                <Stats keys={flags} totalKeys={totalKeys} modifiedKeys={modifiedKeys} />

                {/* Flags - keys */}
                <div className="flex gap-3 border-b border-slate-200">
                    {["properties", "history"].map(tab => (
                        <button
                            key={tab}
                            className={`px-4 py-2 rounded-t-lg text-sm font-medium transition-all ${
                                activeTab === tab
                                    ? "bg-white border border-slate-200 border-b-0 text-blue-600"
                                    : "text-slate-500 hover:text-slate-700"
                            }`}
                            onClick={() => setActiveTab(tab)}
                        >
                            {tab === "properties" ? "Properties" : "History"}
                        </button>
                    ))}
                </div>

                {/* Config Header */}
                {activeTab === "properties" && (

                    <div className="divide-y divide-gray-200">
                        {flags.map(flag => (
                            <div key={flag.key}
                                 className="bg-white rounded-2xl border border-slate-200 shadow-sm hover:shadow-md transition-all p-5 space-y-3">
                                {/* Header with key and controls */}
                                <div className="flex items-start justify-between gap-4">
                                    <div className="flex-1 min-w-0">
                                        <span className="text-xs uppercase tracking-wide text-slate-400">Feature Key</span>
                                        <div className="font-mono text-slate-800 font-semibold break-all text-sm">
                                            {flag.key}
                                        </div>
                                        {flag.comment && (
                                            <div className="text-xs text-slate-500 mt-1">
                                                {flag.comment}
                                            </div>
                                        )}
                                        {flag.expirationDate && (
                                            <div className="flex items-center gap-1.5 mt-2 px-2 py-1 bg-amber-50 border border-amber-200 rounded-md w-fit">
                                                <Clock className="h-3.5 w-3.5 text-amber-600" />
                                                <span className="text-xs font-medium text-amber-700">Expires: {flag.expirationDate}</span>
                                            </div>
                                        )}
                                    </div>
                                    <div className="flex items-center gap-2 flex-shrink-0">
                                        <StateButtonForFlag flag={flag} toggle={handleToggle} />
                                    </div>
                                </div>
                                <InstancesForFlag instances={flag.instances} expected={flag.enabled} />
                            </div>
                        ))}
                    </div>
                )}

                {activeTab === "history" && (
                    <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
                        <h3 className="font-semibold text-slate-800 mb-3 flex items-center gap-2">
                            <Clock className="h-4 w-4" /> Recent Changes
                        </h3>
                        <div className="max-h-96 overflow-auto text-sm">
                            {history.length === 0 ? (
                                <div className="text-slate-400 italic text-center py-4">No recent changes recorded</div>
                            ) : (
                                <table className="w-full text-left border-collapse">
                                    <thead>
                                    <tr className="text-slate-500 text-xs uppercase border-b border-slate-200">
                                        <th className="py-2 px-2">Timestamp</th>
                                        <th className="py-2 px-2">Key</th>
                                        <th className="py-2 px-2">Action</th>
                                        <th className="py-2 px-2">User</th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    {history.map((h, i) => (
                                        <tr
                                            key={i}
                                            className={`text-slate-700 ${i % 2 === 0 ? "bg-slate-50" : "bg-white"}`}
                                        >
                                            <td className="py-2 px-2">{h.timestamp}</td>
                                            <td className="py-2 px-2 font-mono">{h.key}</td>
                                            <td className="py-2 px-2">{h.action}</td>
                                            <td className="py-2 px-2">{h.user}</td>
                                        </tr>
                                    ))}
                                    </tbody>
                                </table>
                            )}
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
};

export default FeatureFlippingPage;

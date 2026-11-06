import React, {useEffect, useState} from "react";
import {AlertTriangle, Ban, Building2, CheckCircle2, Clock, KeyRound, LogOut, Plus, ShieldCheck} from "lucide-react";
import CopyButton from "../components/CopyButton.jsx";
import {getIngestUrl} from "../services/config.js";
import {
    createApiKey,
    createTenant,
    getAllTenants,
    getTenants,
    reasonOf,
    revokeApiKey,
    setApiKeyQuota
} from "../services/tenantApi.js";

const ENV_CHOICES = ['dev', 'staging', 'prod'];

const formatDate = (iso) => iso ? new Date(iso).toLocaleString() : '—';

const ErrorBox = ({message}) => message ? (
    <div role="alert" className="flex items-start gap-2 text-sm text-red-800 bg-red-50 border border-red-300 rounded-lg p-3 mt-3">
        <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" aria-hidden="true"/>
        <span><span className="font-semibold">Error: </span>{message}</span>
    </div>
) : null;

const Card = ({title, icon, children, actions}) => (
    <section className="bg-white rounded-lg shadow-md border border-slate-200 p-6 mb-6">
        <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-slate-800 flex items-center gap-2">{icon}{title}</h2>
            {actions}
        </div>
        {children}
    </section>
);

// Numbers first, the bar only repeats them; past the quota a labelled marker, not a colour.
const Usage = ({used, quota}) => {
    const ratio = quota > 0 ? Math.min(used / quota, 1) : 1;
    const full = used >= quota;
    return (
        <div className="min-w-[160px]">
            <div className="text-sm text-slate-800 tabular-nums">
                {used.toLocaleString()} / {quota.toLocaleString()}
                <span className="text-slate-500"> ({Math.round(ratio * 100)}%)</span>
            </div>
            <div className="h-2 mt-1 bg-slate-200 rounded" aria-hidden="true">
                <div className="h-2 bg-slate-700 rounded" style={{width: `${ratio * 100}%`}}/>
            </div>
            {full && (
                <div className="text-xs font-semibold text-slate-800 mt-1 flex items-center gap-1">
                    <Ban className="h-3 w-3" aria-hidden="true"/> Quota reached: audits refused until midnight UTC
                </div>
            )}
        </div>
    );
};

const Status = ({active}) => active ? (
    <span className="inline-flex items-center gap-1 text-sm text-slate-800">
        <CheckCircle2 className="h-4 w-4" aria-hidden="true"/> Active
    </span>
) : (
    <span className="inline-flex items-center gap-1 text-sm text-slate-500 line-through">
        <Ban className="h-4 w-4" aria-hidden="true"/> Revoked
    </span>
);

const CreateTenant = ({limits, onCreated}) => {
    const [slug, setSlug] = useState('');
    const [envs, setEnvs] = useState(['dev']);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);

    const toggle = (env) => setEnvs(current =>
        current.includes(env) ? current.filter(e => e !== env) : [...current, env]);

    const submit = async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        try {
            await createTenant(slug, envs);
            onCreated();
        } catch (err) {
            setError(reasonOf(err));
        } finally {
            setBusy(false);
        }
    };

    return (
        <Card title="Create your tenant" icon={<Building2 className="h-5 w-5"/>}>
            <p className="text-sm text-slate-600 mb-4">
                A tenant is the domain your applications send their audits to. Only you (and the administrators)
                see its audits. You can create {limits?.tenants ?? 1} tenant(s).
            </p>
            <form onSubmit={submit} className="space-y-4 max-w-md">
                <label className="block">
                    <span className="text-sm font-medium text-slate-700">Tenant name</span>
                    <input value={slug} onChange={e => setSlug(e.target.value.trim())} required
                           pattern="[a-z][a-z0-9]{2,29}" placeholder="acme"
                           className="mt-1 w-full rounded-lg border border-slate-400 px-3 py-2 font-mono focus:outline-none focus:ring-2 focus:ring-slate-400"/>
                    <span className="text-xs text-slate-500">3 to 30 lower-case letters or digits, starting with a letter. It can't be changed.</span>
                </label>
                <fieldset>
                    <legend className="text-sm font-medium text-slate-700">Environments</legend>
                    <div className="flex gap-4 mt-1">
                        {ENV_CHOICES.map(env => (
                            <label key={env} className="flex items-center gap-2 text-sm">
                                <input type="checkbox" checked={envs.includes(env)} onChange={() => toggle(env)}/>
                                <span className="font-mono">{env}</span>
                            </label>
                        ))}
                    </div>
                </fieldset>
                <button type="submit" disabled={busy || envs.length === 0}
                        className="px-4 py-2 rounded-lg bg-slate-800 text-white font-semibold hover:bg-slate-900 disabled:opacity-50">
                    {busy ? 'Creating…' : 'Create the tenant'}
                </button>
            </form>
            <ErrorBox message={error}/>
        </Card>
    );
};

// The key itself, shown once, right after its creation.
const NewKey = ({created, onDone}) => (
    <div className="border-2 border-slate-800 rounded-lg p-4 mb-4 bg-slate-50" role="status">
        <p className="font-semibold text-slate-800 flex items-center gap-2">
            <AlertTriangle className="h-4 w-4" aria-hidden="true"/>
            Copy the key "{created.name}" now: it won't be shown again.
        </p>
        <div className="flex items-center mt-2">
            <code className="flex-1 font-mono text-sm bg-white border border-slate-300 rounded px-3 py-2 break-all">{created.key}</code>
            <CopyButton value={created.key} label="Copy the API key"/>
        </div>
        <button onClick={onDone} className="mt-3 px-3 py-1.5 rounded-lg border border-slate-400 text-sm hover:bg-slate-100">
            I've copied it
        </button>
    </div>
);

const KeyRow = ({apiKey, onRevoke, admin, onQuota}) => {
    const [confirming, setConfirming] = useState(false);
    const [quota, setQuota] = useState(String(apiKey.dailyQuota));
    return (
        <tr className="border-t border-slate-100 align-top">
            <td className="py-3 pr-4 text-sm font-medium text-slate-800">{apiKey.name}</td>
            <td className="py-3 pr-4 font-mono text-sm text-slate-700">{apiKey.prefix}…</td>
            <td className="py-3 pr-4"><Status active={apiKey.active}/></td>
            <td className="py-3 pr-4">
                {apiKey.active ? <Usage used={apiKey.usedToday} quota={apiKey.dailyQuota}/> :
                    <span className="text-sm text-slate-500">Revoked {formatDate(apiKey.revokedAt)}</span>}
                {admin && apiKey.active && (
                    <form className="flex items-center gap-1 mt-2" onSubmit={e => { e.preventDefault(); onQuota(apiKey, Number(quota)); }}>
                        <label className="text-xs text-slate-600" htmlFor={`quota-${apiKey.id}`}>Quota/day</label>
                        <input id={`quota-${apiKey.id}`} type="number" min="0" value={quota} onChange={e => setQuota(e.target.value)}
                               className="w-24 rounded border border-slate-400 px-2 py-0.5 text-sm"/>
                        <button type="submit" className="text-xs px-2 py-1 rounded border border-slate-400 hover:bg-slate-100">Save</button>
                    </form>
                )}
            </td>
            <td className="py-3 pr-4 text-sm text-slate-600">{formatDate(apiKey.lastUsedAt)}</td>
            <td className="py-3 text-right whitespace-nowrap">
                {apiKey.active && (confirming ? (
                    <span className="inline-flex items-center gap-2 text-sm">
                        Revoke it?
                        <button onClick={() => onRevoke(apiKey)} className="px-2 py-1 rounded bg-slate-800 text-white font-semibold">Yes, revoke</button>
                        <button onClick={() => setConfirming(false)} className="px-2 py-1 rounded border border-slate-400">Cancel</button>
                    </span>
                ) : (
                    <button onClick={() => setConfirming(true)}
                            className="inline-flex items-center gap-1 px-2 py-1 rounded border border-slate-400 text-sm hover:bg-slate-100">
                        <Ban className="h-4 w-4" aria-hidden="true"/> Revoke
                    </button>
                ))}
            </td>
        </tr>
    );
};

const KeysTable = ({tenant, admin, onChanged, setError}) => {
    const revoke = async (apiKey) => {
        try {
            await revokeApiKey(tenant.slug, apiKey.id);
            onChanged();
        } catch (err) {
            setError(reasonOf(err));
        }
    };
    const changeQuota = async (apiKey, quota) => {
        try {
            await setApiKeyQuota(apiKey.id, quota);
            onChanged();
        } catch (err) {
            setError(reasonOf(err));
        }
    };
    if (tenant.apiKeys.length === 0) {
        return <p className="text-sm text-slate-500">No API key yet.</p>;
    }
    return (
        <div className="overflow-x-auto">
            <table className="w-full text-left">
                <thead>
                <tr className="text-xs uppercase text-slate-500">
                    <th className="pb-2 pr-4">Name</th>
                    <th className="pb-2 pr-4">Key</th>
                    <th className="pb-2 pr-4">Status</th>
                    <th className="pb-2 pr-4">Audits today (UTC)</th>
                    <th className="pb-2 pr-4">Last used</th>
                    <th className="pb-2"><span className="sr-only">Actions</span></th>
                </tr>
                </thead>
                <tbody>
                {tenant.apiKeys.map(k => (
                    <KeyRow key={k.id} apiKey={k} admin={admin} onRevoke={revoke} onQuota={changeQuota}/>
                ))}
                </tbody>
            </table>
        </div>
    );
};

const TenantCard = ({tenant, limits, admin, onChanged}) => {
    const [name, setName] = useState('');
    const [created, setCreated] = useState(null);
    const [error, setError] = useState(null);
    const ingestUrl = getIngestUrl();
    const activeKeys = tenant.apiKeys.filter(k => k.active).length;
    const keyLimit = limits?.keysPerTenant ?? 3;

    const addKey = async (e) => {
        e.preventDefault();
        setError(null);
        try {
            setCreated(await createApiKey(tenant.slug, name));
            setName('');
            onChanged();
        } catch (err) {
            setError(reasonOf(err));
        }
    };

    return (
        <Card title={<>Tenant <span className="font-mono">{tenant.slug}</span></>} icon={<Building2 className="h-5 w-5"/>}>
            <dl className="grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm mb-6">
                <div>
                    <dt className="text-slate-500">Domain (in your apps' configuration)</dt>
                    <dd className="font-mono text-slate-800 flex items-center">{tenant.domain}<CopyButton value={tenant.domain} label="Copy the domain"/></dd>
                </div>
                <div>
                    <dt className="text-slate-500">Environments</dt>
                    <dd className="font-mono text-slate-800">{tenant.envs.join(', ')}</dd>
                </div>
                <div>
                    <dt className="text-slate-500">Ingestion gateway</dt>
                    <dd className="font-mono text-slate-800 flex items-center break-all">
                        {ingestUrl || 'not configured'}{ingestUrl && <CopyButton value={ingestUrl}/>}
                    </dd>
                </div>
            </dl>
            <p className="text-sm text-slate-600 mb-4">
                Your applications send their audits to the gateway with an API key in the <code className="font-mono">X-Kockpit-Api-Key</code> header.
                Each key may send {(limits?.defaultDailyQuota ?? 0).toLocaleString()} audits per day (UTC); beyond, the gateway answers 429 until midnight UTC.
            </p>

            <h3 className="text-base font-semibold text-slate-800 flex items-center gap-2 mb-3">
                <KeyRound className="h-4 w-4"/> API keys <span className="text-sm font-normal text-slate-500">({activeKeys} of {keyLimit} active)</span>
            </h3>
            {created && <NewKey created={created} onDone={() => setCreated(null)}/>}
            <KeysTable tenant={tenant} admin={admin} onChanged={onChanged} setError={setError}/>
            <form onSubmit={addKey} className="flex items-end gap-2 mt-4">
                <label className="flex flex-col">
                    <span className="text-sm font-medium text-slate-700">New key name</span>
                    <input value={name} onChange={e => setName(e.target.value)} required maxLength={100} placeholder="e.g. laptop, ci"
                           className="mt-1 w-64 rounded-lg border border-slate-400 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-slate-400"/>
                </label>
                <button type="submit" disabled={activeKeys >= keyLimit}
                        className="inline-flex items-center gap-1 px-4 py-2 rounded-lg bg-slate-800 text-white font-semibold hover:bg-slate-900 disabled:opacity-50">
                    <Plus className="h-4 w-4"/> Create a key
                </button>
            </form>
            {activeKeys >= keyLimit && (
                <p className="text-xs text-slate-600 mt-1">Key limit reached: revoke a key to create another one.</p>
            )}
            <ErrorBox message={error}/>
            <UsageSnippet tenant={tenant} ingestUrl={ingestUrl}/>
        </Card>
    );
};

const Snippet = ({title, code}) => (
    <div className="mt-3">
        <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-slate-700">{title}</span>
            <CopyButton value={code} label={`Copy: ${title}`}/>
        </div>
        <pre className="mt-1 bg-slate-900 text-slate-100 text-xs rounded-lg p-3 overflow-x-auto"><code>{code}</code></pre>
    </div>
);

// What a tester pastes into a Spring Boot application: the starters and the gateway settings.
const UsageSnippet = ({tenant, ingestUrl}) => {
    const maven = `<!-- audits of the HTTP calls, sent to the gateway -->
<dependency>
    <groupId>org.kockpit</groupId>
    <artifactId>kockpit-features-audit-starters-http</artifactId>
    <version>1.2.0-SNAPSHOT</version>
</dependency>
<!-- optional: heartbeats, feature flags and dynamic configuration through the gateway -->
<dependency>
    <groupId>org.kockpit</groupId>
    <artifactId>kockpit-core-communication-http</artifactId>
    <version>1.2.0-SNAPSHOT</version>
</dependency>`;
    const yaml = `kockpit:
  domain: ${tenant.domain}
  env: ${tenant.envs[0] ?? 'dev'}          # one of: ${tenant.envs.join(', ')}
  app-id: \${spring.application.name}
  gateway:
    url: ${ingestUrl || 'https://ingest.example.com'}
    api-key: \${KOCKPIT_API_KEY}   # from the environment, never in source control`;
    return (
        <details className="mt-6 border-t border-slate-200 pt-4">
            <summary className="cursor-pointer text-base font-semibold text-slate-800">Use it in a Spring Boot application</summary>
            <Snippet title="pom.xml" code={maven}/>
            <Snippet title="application.yml" code={yaml}/>
            <p className="text-sm text-slate-600 mt-3">
                Audits are buffered and sent in batches. Over the daily quota they are dropped until midnight UTC and the application
                logs it once; with a revoked key, once every five minutes.
            </p>
        </details>
    );
};

const AdminTenants = ({onChanged}) => {
    const [tenants, setTenants] = useState(null);
    const [error, setError] = useState(null);
    const load = () => getAllTenants().then(setTenants).catch(err => setError(reasonOf(err)));
    useEffect(() => { load(); }, []);
    const changed = () => { load(); onChanged(); };

    return (
        <Card title="All tenants (administrator)" icon={<ShieldCheck className="h-5 w-5"/>}>
            {tenants === null && !error && <p className="text-sm text-slate-500">Loading…</p>}
            {tenants && tenants.length === 0 && <p className="text-sm text-slate-500">No tenant yet.</p>}
            {tenants && tenants.map(t => (
                <div key={t.slug} className="border-t border-slate-200 pt-4 mt-4 first:border-0 first:pt-0 first:mt-0">
                    <p className="text-sm mb-2">
                        <span className="font-mono font-semibold">{t.slug}</span>
                        <span className="text-slate-500"> · owner {t.owner} · envs {t.envs.join(', ')} · created {formatDate(t.createdAt)}</span>
                    </p>
                    <KeysTable tenant={t} admin={true} onChanged={changed} setError={setError}/>
                </div>
            ))}
            <ErrorBox message={error}/>
        </Card>
    );
};

// onboarding: shown alone (no sidebar) while the user can't see any manifest yet
const TenantPage = ({onboarding = false, onLogout}) => {
    const [data, setData] = useState(null);
    const [error, setError] = useState(null);

    const load = () => getTenants().then(d => { setData(d); setError(null); }).catch(err => setError(reasonOf(err)));
    useEffect(() => { load(); }, []);

    // the console loads the manifests (domains/envs) once: reload it to show the new tenant's
    const onTenantCreated = () => window.location.assign('/tenant');

    return (
        <div className="min-h-screen bg-slate-50">
            <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
                <div className="mb-8 flex items-center justify-between">
                    <h1 className="text-3xl font-bold text-slate-800 flex items-center gap-3">
                        <KeyRound className="h-8 w-8"/> Tenant &amp; API keys
                    </h1>
                    {onboarding && onLogout && (
                        <button onClick={onLogout} className="inline-flex items-center gap-2 px-3 py-2 rounded-lg border border-slate-400 hover:bg-slate-100">
                            <LogOut className="h-4 w-4"/> Sign out
                        </button>
                    )}
                </div>
                {onboarding && (
                    <p className="text-slate-600 mb-6">Welcome! Create your tenant to start sending audits and browse them here.</p>
                )}
                {!data && !error && (
                    <p className="text-slate-600 flex items-center gap-2"><Clock className="h-4 w-4 animate-spin"/> Loading…</p>
                )}
                <ErrorBox message={error}/>
                {data && (
                    <>
                        {data.tenants.map(t => (
                            <TenantCard key={t.slug} tenant={t} limits={data.limits} admin={data.admin} onChanged={load}/>
                        ))}
                        {(data.tenants.length < (data.limits?.tenants ?? 1) || data.admin) && (
                            <CreateTenant limits={data.limits} onCreated={onTenantCreated}/>
                        )}
                        {data.admin && <AdminTenants onChanged={load}/>}
                    </>
                )}
            </div>
        </div>
    );
};

export default TenantPage;

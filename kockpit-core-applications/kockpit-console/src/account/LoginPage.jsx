import React, {useState} from "react";
import {AlertTriangle, LogIn, UserPlus} from "lucide-react";
import {reasonOf, signin, signup} from "../services/tenantApi.js";

// Sign in, or create an account, with the backend's local accounts.
// notice: why the user is asked to sign in (an invitation to join a tenant)
const LoginPage = ({onSignedIn, notice}) => {
    const [mode, setMode] = useState('login');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);
    const creating = mode === 'signup';

    const submit = async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        try {
            const me = creating ? await signup(email, password) : await signin(email, password);
            onSignedIn(me);
        } catch (err) {
            const retryAfter = err?.response?.headers?.['retry-after'];
            setError(reasonOf(err) + (retryAfter ? ` (retry in ${Math.ceil(retryAfter / 60)} min)` : ''));
        } finally {
            setBusy(false);
        }
    };

    const tab = (value, label, icon) => (
        <button
            type="button"
            role="tab"
            aria-selected={mode === value}
            onClick={() => { setMode(value); setError(null); }}
            className={`flex-1 flex items-center justify-center gap-2 py-2 text-sm font-semibold border-b-4 transition-colors ${
                mode === value ? 'border-slate-800 text-slate-900' : 'border-transparent text-slate-500 hover:text-slate-800'}`}
        >
            {icon} {label}
        </button>
    );

    return (
        <div className="min-h-screen flex items-center justify-center bg-slate-100 p-4">
            <div className="w-full max-w-sm bg-white rounded-xl shadow-lg border border-slate-200">
                <div className="flex justify-center pt-8 pb-4 bg-slate-800 rounded-t-xl">
                    <img src="/kockpit-logo.png" alt="Kockpit" className="h-10"/>
                </div>
                {notice && (
                    <p className="px-6 pt-4 text-sm text-slate-700">{notice}</p>
                )}
                <div className="flex border-b border-slate-200" role="tablist">
                    {tab('login', 'Sign in', <LogIn className="h-4 w-4" aria-hidden="true"/>)}
                    {tab('signup', 'Create an account', <UserPlus className="h-4 w-4" aria-hidden="true"/>)}
                </div>
                <form onSubmit={submit} className="p-6 space-y-4">
                    <label className="block">
                        <span className="text-sm font-medium text-slate-700">Email</span>
                        <input type="email" required autoComplete="email" value={email}
                               onChange={e => setEmail(e.target.value)}
                               className="mt-1 w-full rounded-lg border border-slate-400 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-slate-400"/>
                    </label>
                    <label className="block">
                        <span className="text-sm font-medium text-slate-700">Password</span>
                        <input type="password" required minLength={creating ? 10 : undefined}
                               autoComplete={creating ? 'new-password' : 'current-password'} value={password}
                               onChange={e => setPassword(e.target.value)}
                               className="mt-1 w-full rounded-lg border border-slate-400 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-slate-400"/>
                        {creating && <span className="text-xs text-slate-500">At least 10 characters</span>}
                    </label>
                    {error && (
                        <div role="alert" className="flex items-start gap-2 text-sm text-red-800 bg-red-50 border border-red-300 rounded-lg p-3">
                            <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" aria-hidden="true"/>
                            <span><span className="font-semibold">Error: </span>{error}</span>
                        </div>
                    )}
                    <button type="submit" disabled={busy}
                            className="w-full py-2 rounded-lg bg-slate-800 text-white font-semibold hover:bg-slate-900 disabled:opacity-50">
                        {busy ? 'Please wait…' : creating ? 'Create my account' : 'Sign in'}
                    </button>
                </form>
            </div>
        </div>
    );
};

export default LoginPage;

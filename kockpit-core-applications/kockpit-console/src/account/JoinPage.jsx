import React, {useEffect, useState} from "react";
import {AlertTriangle, CheckCircle2, Clock, LogOut, UserPlus} from "lucide-react";
import {acceptInvitation, previewInvitation, reasonOf} from "../services/tenantApi.js";

const formatDate = (iso) => iso ? new Date(iso).toLocaleString() : '—';

// /join#<code>: the signed-in account looks at the invitation, then joins the tenant.
const JoinPage = ({code, user, onLogout}) => {
    const [preview, setPreview] = useState(null);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        if (!code) {
            setError('This link holds no invitation: ask the tenant\'s owner for the full link.');
            return;
        }
        previewInvitation(code).then(setPreview).catch(err => setError(reasonOf(err)));
    }, [code]);

    // a full reload: the console then loads the manifests the account can now see
    const openConsole = () => window.location.assign('/');

    const join = async () => {
        setBusy(true);
        setError(null);
        try {
            await acceptInvitation(code);
            openConsole();
        } catch (err) {
            setError(reasonOf(err));
            setBusy(false);
        }
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-slate-100 p-4">
            <div className="w-full max-w-md bg-white rounded-xl shadow-lg border border-slate-200 p-6">
                <h1 className="text-xl font-bold text-slate-800 flex items-center gap-2 mb-4">
                    <UserPlus className="h-5 w-5" aria-hidden="true"/> Join a tenant
                </h1>
                <p className="text-sm text-slate-600 mb-4">
                    Signed in as <span className="font-mono">{user?.clientPrincipal?.userDetails}</span>
                </p>

                {!preview && !error && (
                    <p className="text-sm text-slate-600 flex items-center gap-2"><Clock className="h-4 w-4 animate-spin" aria-hidden="true"/> Checking the invitation…</p>
                )}

                {preview && (
                    <div className="text-sm text-slate-700 space-y-2 mb-4">
                        <p>
                            {preview.invitedBy ? <><span className="font-mono">{preview.invitedBy}</span> invites you to</> : 'You are invited to'} the
                            tenant <span className="font-mono font-semibold">{preview.tenant}</span>: you will see its audits in the console.
                        </p>
                        <p className="text-slate-500">Valid until {formatDate(preview.expiresAt)}.</p>
                    </div>
                )}

                {preview?.alreadyMember && (
                    <div className="flex items-start gap-2 text-sm text-slate-800 bg-slate-50 border border-slate-300 rounded-lg p-3 mb-4">
                        <CheckCircle2 className="h-4 w-4 mt-0.5 flex-shrink-0" aria-hidden="true"/>
                        <span><span className="font-semibold">Already a member: </span>you can see this tenant already.</span>
                    </div>
                )}

                {error && (
                    <div role="alert" className="flex items-start gap-2 text-sm text-red-800 bg-red-50 border border-red-300 rounded-lg p-3 mb-4">
                        <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" aria-hidden="true"/>
                        <span><span className="font-semibold">Error: </span>{error}</span>
                    </div>
                )}

                <div className="flex flex-wrap gap-2">
                    {preview && !preview.alreadyMember && (
                        <button type="button" onClick={join} disabled={busy}
                                className="inline-flex items-center gap-1 px-4 py-2 rounded-lg bg-slate-800 text-white font-semibold hover:bg-slate-900 disabled:opacity-50">
                            <UserPlus className="h-4 w-4" aria-hidden="true"/> Join {preview.tenant}
                        </button>
                    )}
                    <button type="button" onClick={openConsole}
                            className="px-4 py-2 rounded-lg border border-slate-400 text-slate-800 font-semibold hover:bg-slate-100">
                        {preview?.alreadyMember ? 'Open the console' : 'Not now'}
                    </button>
                    {/* an invitation for another email: sign in with the right account */}
                    <button type="button" onClick={onLogout}
                            className="inline-flex items-center gap-1 px-4 py-2 rounded-lg text-slate-700 hover:bg-slate-100">
                        <LogOut className="h-4 w-4" aria-hidden="true"/> Use another account
                    </button>
                </div>
            </div>
        </div>
    );
};

export default JoinPage;

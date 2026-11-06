import React, {useEffect, useState} from 'react';
import {BrowserRouter, Navigate, Route, Routes} from 'react-router-dom';
import './index.css';
import Sidebar from "./sidebar/Sidebar.jsx";
import AuditListPage from "./audits/pages/AuditListPage.jsx";
import DetailsPage from "./audits/pages/DetailsPage.jsx";
import DomainEnv from "./components/DomainEnv.jsx";
import {login, logout, toPrincipal} from "./services/api.js";
import {isLocalAuth} from "./services/config.js";
import LoginPage from "./account/LoginPage.jsx";
import JoinPage from "./account/JoinPage.jsx";
import TenantPage from "./account/TenantPage.jsx";
import FeatureFlippingPage from "./feature-flipping/feature.jsx";
import AppIdDashboard from "./home/Home.jsx";
import HealthIndicatorsDashboard from "./health/Health.jsx";
import ConfigManager from "./dyna-config/dyna-config.jsx";
import CachePage from "./cache/CachePage.jsx";
import ManifestPage from "./manifest/ManifestPage.jsx";
import SettingsPage from "./settings/SettingsPage.jsx";

function App() {
    const [collapsed, setCollapsed] = useState(false);
    const [domain, setDomain] = useState(null);
    const [env, setEnv] = useState(null);
    const [configs, setConfigs] = useState({});
    const [config, setConfig] = useState();
    const [loading, setLoading] = useState(true);
    const [user, setUser] = useState('');
    const [configIndex, setConfigIndex] = useState(0);
    // local accounts: the user sees no manifest yet (no tenant)
    const [noManifests, setNoManifests] = useState(false);
    const localAuth = isLocalAuth();

    useEffect(() => {
        if (localAuth) {
            // GET /me: 401 when not signed in, the login page is shown then
            login().then(setUser).catch(() => setUser(null)).finally(() => setLoading(false));
            return;
        }
        // Check if user is stored from OAuth callback
        const storedUser = localStorage.getItem('cognito_user');
        if (storedUser) {
            try {
                const user = JSON.parse(storedUser);
                setUser(user);
                setLoading(false);
                return;
            } catch (e) {
                console.error('Error parsing stored user:', e);
                localStorage.removeItem('cognito_user');
            }
        }

        // Normal login flow
        login().then(logged => {
            setUser(logged);
            setLoading(false);
        }).catch(error => {
            console.error('Login failed:', error);
            setLoading(false);
        });
    }, []);

    function onDomainEnvChanged(domain, env, selectedIndex) {
        console.log(`domain ${domain} / env ${env} selected.`);
        setDomain(domain);
        setEnv(env);
        setConfigIndex(selectedIndex);

        let _cfg = configs[selectedIndex];
        if (_cfg.env === env && _cfg.domain === domain) {
            setConfig(_cfg);
        }
    }

    function onConfigLoaded(configs, selectedIndex) {
        setConfigs(configs);
        const cfg = configs[selectedIndex];
        if (!cfg) {
            return;
        }
        setConfig(cfg);
        setDomain(cfg.domain);
        setEnv(cfg.env);
    }

    if (loading) return <div>Loading ...</div>;

    // an invitation link (/join#<code>): sign in or sign up first, then join the tenant
    const joining = localAuth && window.location.pathname === '/join';
    const joinCode = joining ? decodeURIComponent(window.location.hash.replace(/^#/, '')) : null;

    if (localAuth && !user) {
        return <LoginPage onSignedIn={me => setUser(toPrincipal(me))}
                          notice={joining ? 'Sign in, or create an account, to join the tenant you are invited to.' : null}/>;
    }

    if (joining) {
        // signed out, the same link (and its code) shows the sign-in page again
        return <JoinPage code={joinCode} user={user} onLogout={() => logout().finally(() => window.location.reload())}/>;
    }

    const signOut = () => logout().finally(() => window.location.assign('/'));

    return (
        <BrowserRouter>
        <div className="App">
                <div className="screens-container">
                    {
                        config && user ?
                            <Sidebar collapsed={collapsed} setCollapsed={setCollapsed} config={config} user={user} />
                            : null
                    }
                    <div>
                        <div className="flex justify-end p-2">
                            <DomainEnv domainEnvChanged={onDomainEnvChanged} onConfigLoaded={onConfigLoaded} selectedIndex={configIndex} onSelectedIndex={setConfigIndex}
                                       onEmpty={localAuth ? () => setNoManifests(true) : undefined} />
                        </div>
                        <div className='screens-section-container'>
                            <Routes>
                                <Route path='*' element={
                                    config && user ? (
                                        <div className="flex">
                                            {/* margin = the fixed sidebar's width (w-20 / w-64); flex-1 min-w-0 keeps the column
                                                within the window, so wide content scrolls in place instead of under the sidebar */}
                                            <div className={`${collapsed ? 'ml-20' : 'ml-64'} p-6 flex-1 min-w-0 transition-all duration-300`}>
                                                <Routes>
                                                    <Route path='/home' element={<AppIdDashboard domain={domain} env={env} />} />
                                                    <Route path='/health' element={<HealthIndicatorsDashboard domain={domain} env={env} />} />
                                                    <Route path='/audits' element={<AuditListPage domain={domain} env={env} config={config} selectedIdx={configIndex} />} />
                                                    <Route path="/audits/:id" element={<DetailsPage domain={domain} env={env} selectedIdx={configIndex} />} />
                                                    <Route path='/feature-flipping' element={<FeatureFlippingPage domain={domain} env={env} config={config} />} />
                                                    <Route path='/feature-flipping/:service' element={<FeatureFlippingPage domain={domain} env={env} config={config} />} />
                                                    <Route path='/dyna-config' element={<ConfigManager domain={domain} env={env} config={config} />} />
                                                    <Route path='/dyna-config/:service' element={<ConfigManager domain={domain} env={env} config={config} />} />
                                                    <Route path='/cache' element={<CachePage domain={domain} env={env} />} />
                                                    <Route path='/manifest' element={<ManifestPage />} />
                                                    <Route path='/settings' element={<SettingsPage />} />
                                                    {localAuth && <Route path='/tenant' element={<TenantPage />} />}
                                                    <Route path="*" element={<Navigate to="/home" replace={true}/>}/>
                                                </Routes>
                                            </div>
                                        </div>
                                    ) : noManifests ? (
                                        <TenantPage onboarding={true} onLogout={signOut} />
                                    ) : (
                                        <div className="flex items-center justify-center min-h-screen">
                                            <div>Loading...</div>
                                        </div>
                                    )
                                } />
                            </Routes>
                        </div>
                    </div>
                </div>
        </div>
        </BrowserRouter>
    );
}

export default App;

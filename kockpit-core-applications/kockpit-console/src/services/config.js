export const getApiBase = () => {
    // Try runtime config first, then fallback to build-time env, then default
    const runtimeBase = window.ENV?.VITE_API_BASE;
    if (runtimeBase && !runtimeBase.startsWith('${')) {
        return runtimeBase;
    }
    return import.meta.env.VITE_API_BASE || '/api';
}

// Refresh period of the feature-flipping instances (runtime config, set short on Kubernetes where polling is cheap).
export const getFeatureFlippingRefreshMs = () => {
    const value = Number(window.ENV?.FEATURE_FLIPPING_REFRESH_MS);
    return Number.isFinite(value) && value >= 1000 ? value : 15000;
}

// A runtime value, unless its placeholder was left as is (not set when the container started).
const runtime = (name) => {
    const value = window.ENV?.[name];
    return value && !value.startsWith('${') ? value : '';
}

// "local": accounts of the backend itself (signup/login pages, tenants and API keys).
export const getAuthMode = () => runtime('AUTH_MODE') || import.meta.env.VITE_AUTH_MODE || '';

export const isLocalAuth = () => getAuthMode() === 'local';

// Public URL of the ingestion gateway the apps send their audits to (empty when not configured).
export const getIngestUrl = () => runtime('INGEST_URL') || import.meta.env.VITE_INGEST_URL || '';

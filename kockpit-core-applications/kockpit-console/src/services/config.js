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

window.ENV = {
    VITE_API_BASE: '${VITE_API_BASE}', // Will be replaced at runtime
    // feature-flipping page: how often the instances' acknowledgements are refreshed (ms, default 15000)
    FEATURE_FLIPPING_REFRESH_MS: '${FEATURE_FLIPPING_REFRESH_MS}',
    // "local": the backend's own accounts (signup/login pages, tenants and API keys); empty: Cognito/Azure/dev
    AUTH_MODE: '${AUTH_MODE}',
    // public URL of the ingestion gateway, shown on the "Tenant & API keys" page
    INGEST_URL: '${INGEST_URL}'
}

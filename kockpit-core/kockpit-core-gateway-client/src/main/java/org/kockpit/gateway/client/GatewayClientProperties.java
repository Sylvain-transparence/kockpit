package org.kockpit.gateway.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the Kockpit ingestion gateway is and the tenant's API key, from the console's
 * "Tenant &amp; API keys" page.
 *
 * <pre>
 * kockpit:
 *   gateway:
 *     url: https://ingest.example.com
 *     api-key: ${KOCKPIT_API_KEY}
 * </pre>
 */
@ConfigurationProperties("kockpit.gateway")
public class GatewayClientProperties {

    /** Base URL of the gateway, e.g. https://ingest.example.com (no /ingest path). */
    private String url;

    /** The tenant's API key (kpk_...). Keep it out of source control: pass it from the environment. */
    private String apiKey;

    private Duration connectTimeout = Duration.ofSeconds(5);

    /** Per request, response included. */
    private Duration requestTimeout = Duration.ofSeconds(10);

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}

package org.kockpit.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyFilterTest {

    @Test
    void an_ip_sending_unknown_keys_is_slowed_down() throws Exception {
        ApiKeyStore store = mock(ApiKeyStore.class);
        when(store.find(anyString())).thenReturn(Optional.empty());
        GatewayProperties properties = new GatewayProperties();
        properties.getKeys().setFailuresPerIpPerMinute(3);
        ApiKeyFilter filter = new ApiKeyFilter(store, new RateLimiter(Clock.systemUTC()), properties);

        int[] statuses = new int[5];
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingest/v1/audits");
            request.setRemoteAddr("203.0.113.7");
            request.addHeader(ApiKeyFilter.HEADER, "kpk_guess" + i);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            statuses[i] = response.getStatus();
        }

        assertThat(statuses).containsExactly(401, 401, 401, 429, 429);
        MockHttpServletRequest otherIp = new MockHttpServletRequest("POST", "/ingest/v1/audits");
        otherIp.setRemoteAddr("198.51.100.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(otherIp, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void only_ingest_paths_need_a_key() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(mock(ApiKeyStore.class), new RateLimiter(Clock.systemUTC()), new GatewayProperties());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/favicon.ico"), response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}

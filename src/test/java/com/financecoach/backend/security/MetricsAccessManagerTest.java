package com.financecoach.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsAccessManagerTest {

    @Test
    void withCorrectToken_isAllowed() {
        MetricsAccessManager manager = new MetricsAccessManager("scrape-secret", false);

        assertThat(manager.isAllowed(request("Bearer scrape-secret"))).isTrue();
    }

    @Test
    void withWrongOrMissingToken_isDenied() {
        MetricsAccessManager manager = new MetricsAccessManager("scrape-secret", false);

        assertThat(manager.isAllowed(request("Bearer wrong"))).isFalse();
        assertThat(manager.isAllowed(request(null))).isFalse();
        assertThat(manager.isAllowed(request("scrape-secret"))).isFalse();
    }

    @Test
    void withNoTokenConfigured_isAlwaysDenied() {
        MetricsAccessManager manager = new MetricsAccessManager("", false);

        assertThat(manager.isAllowed(request("Bearer "))).isFalse();
    }

    @Test
    void whenAnonymousAllowed_forLocalDevelopment_isAllowed() {
        MetricsAccessManager manager = new MetricsAccessManager("", true);

        assertThat(manager.isAllowed(request(null))).isTrue();
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}

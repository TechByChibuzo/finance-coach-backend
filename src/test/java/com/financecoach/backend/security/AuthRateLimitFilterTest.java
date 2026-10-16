package com.financecoach.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthRateLimitFilter(new RateLimiter(), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void login_isBlockedAfterTenAttemptsFromTheSameIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/api/auth/login", "1.2.3.4").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = post("/api/auth/login", "1.2.3.4");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void otherIps_areNotAffected() throws Exception {
        for (int i = 0; i < 11; i++) {
            post("/api/auth/login", "1.2.3.4");
        }

        assertThat(post("/api/auth/login", "5.6.7.8").getStatus()).isEqualTo(200);
    }

    @Test
    void usesTheLastForwardedForEntry_soClientsCantSpoofTheirIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            postVia("/api/auth/login", "spoofed-" + i + ", 9.9.9.9");
        }

        assertThat(postVia("/api/auth/login", "another-spoof, 9.9.9.9").getStatus()).isEqualTo(429);
    }

    @Test
    void endpointsWithoutALimit_areNotFiltered() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(post("/api/budgets", "1.2.3.4").getStatus()).isEqualTo(200);
        }
    }

    private MockHttpServletResponse post(String path, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletResponse postVia(String path, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

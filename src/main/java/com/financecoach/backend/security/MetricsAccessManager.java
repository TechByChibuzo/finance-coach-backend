package com.financecoach.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * Guards the non-health actuator endpoints (prometheus, metrics). They expose business
 * metrics and JVM internals, so in production a scraper must send
 * "Authorization: Bearer ${METRICS_TOKEN}". Local development can allow anonymous scraping.
 */
@Component
public class MetricsAccessManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final String metricsToken;
    private final boolean allowAnonymous;

    public MetricsAccessManager(@Value("${app.metrics.token:}") String metricsToken,
                                @Value("${app.metrics.allow-anonymous:false}") boolean allowAnonymous) {
        this.metricsToken = metricsToken;
        this.allowAnonymous = allowAnonymous;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        return new AuthorizationDecision(isAllowed(context.getRequest()));
    }

    boolean isAllowed(HttpServletRequest request) {
        if (allowAnonymous) {
            return true;
        }
        if (!StringUtils.hasText(metricsToken)) {
            return false; // no token configured: metrics are not reachable over HTTP
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }

        // Constant-time comparison so the token can't be guessed byte by byte
        return MessageDigest.isEqual(
                header.substring(7).getBytes(StandardCharsets.UTF_8),
                metricsToken.getBytes(StandardCharsets.UTF_8));
    }
}

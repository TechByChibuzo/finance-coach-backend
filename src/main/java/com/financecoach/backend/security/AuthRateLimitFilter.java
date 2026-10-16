package com.financecoach.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Per-IP rate limits on the public auth endpoints, to slow down password guessing,
 * mass sign-ups and reset-email spam.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private record Limit(int requests, Duration window) {}

    private static final Map<String, Limit> LIMITS = Map.of(
            "/api/auth/login", new Limit(10, Duration.ofMinutes(5)),
            "/api/auth/register", new Limit(5, Duration.ofHours(1)),
            "/api/auth/forgot-password", new Limit(5, Duration.ofHours(1)),
            "/api/auth/reset-password", new Limit(10, Duration.ofHours(1))
    );

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(ObjectMapper objectMapper) {
        this(new RateLimiter(), objectMapper);
    }

    AuthRateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !LIMITS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        Limit limit = LIMITS.get(path);
        String clientIp = getClientIp(request);

        long retryAfterSeconds = rateLimiter.tryAcquire(path + "|" + clientIp, limit.requests(), limit.window());
        if (retryAfterSeconds > 0) {
            logger.warn("Rate limit exceeded for {} from {}", path, clientIp);
            writeTooManyRequests(request, response, retryAfterSeconds);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The ALB appends the address it received the request from to X-Forwarded-For, so the
     * last entry is the one we can trust; earlier entries can be set by the client.
     */
    private String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String[] hops = forwardedFor.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                      long retryAfterSeconds) throws IOException {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                "Too many attempts. Please try again later.",
                "RATE_LIMITED"
        );
        error.setPath(request.getRequestURI());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}

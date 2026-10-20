package com.financecoach.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-IP rate limits on the public auth endpoints, to slow down password guessing,
 * mass sign-ups and reset-email spam.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private record Limit(int requests, Duration window) {}

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]+$");

    private static final Map<String, Limit> LIMITS = Map.of(
            "/api/auth/login", new Limit(10, Duration.ofMinutes(5)),
            "/api/auth/register", new Limit(5, Duration.ofHours(1)),
            "/api/auth/forgot-password", new Limit(5, Duration.ofHours(1)),
            "/api/auth/reset-password", new Limit(10, Duration.ofHours(1))
    );

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @Autowired
    public AuthRateLimitFilter(ObjectMapper objectMapper) {
        this(new RateLimiter(), objectMapper);
    }

    AuthRateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !LIMITS.containsKey(normalizePath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = normalizePath(request);
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
     * Match the path the way a client could vary it: drop ";params", decode percent-escapes
     * (Spring routes "/api/auth/log%69n" to the login handler), collapse "//", ignore a
     * trailing "/" and case. Spring Security's firewall rejects some of these; normalizing
     * here means the limit can't be skipped either way.
     */
    private String normalizePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return "";
        }
        int semicolon = path.indexOf(';');
        if (semicolon >= 0) {
            path = path.substring(0, semicolon);
        }
        try {
            path = UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed escape: Spring won't route it to a handler either, keep the raw path
        }
        path = path.replaceAll("/{2,}", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }

    /**
     * X-Forwarded-For is only trusted when the request comes from a private-network address,
     * i.e. our load balancer inside the VPC. The ALB appends the address it received the
     * request from, so the last entry is the one to use; earlier entries are client-supplied.
     * Requests that reach the app directly are keyed by their own address.
     */
    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (StringUtils.hasText(forwardedFor) && isPrivateAddress(remoteAddr)) {
            String[] hops = forwardedFor.split(",");
            String clientIp = hops[hops.length - 1].trim();
            if (isIpLiteral(clientIp)) {
                return clientIp;
            }
        }
        return remoteAddr;
    }

    private boolean isIpLiteral(String value) {
        return IPV4.matcher(value).matches() || (value.contains(":") && IPV6.matcher(value).matches());
    }

    private boolean isPrivateAddress(String address) {
        // Only parse literals so InetAddress never does a DNS lookup
        if (address == null || !isIpLiteral(address)) {
            return false;
        }
        try {
            InetAddress inet = InetAddress.getByName(address);
            if (inet.isLoopbackAddress() || inet.isSiteLocalAddress() || inet.isLinkLocalAddress()) {
                return true;
            }
            // IPv6 unique local addresses (fc00::/7)
            return inet instanceof Inet6Address && (inet.getAddress()[0] & 0xfe) == 0xfc;
        } catch (UnknownHostException e) {
            return false;
        }
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

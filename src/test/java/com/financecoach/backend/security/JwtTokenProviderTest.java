package com.financecoach.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret",
                "test-secret-key-that-is-long-enough-for-hmac-sha256-minimum-length");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationMs", 86400000L);
    }

    @Test
    void generateToken_returnsNonNullToken() {
        String token = jwtTokenProvider.generateToken(UUID.randomUUID(), "test@example.com", 0);
        assertThat(token).isNotNull().isNotEmpty();
    }

    @Test
    void getUserIdFromToken_returnsCorrectUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.generateToken(userId, "test@example.com", 0);
        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(userId);
    }

    @Test
    void getEmailFromToken_returnsCorrectEmail() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenProvider.generateToken(userId, "test@example.com", 0);
        assertThat(jwtTokenProvider.getEmailFromToken(token)).isEqualTo("test@example.com", 0);
    }

    @Test
    void getTokenVersionFromToken_returnsTheVersionTheTokenWasIssuedWith() {
        String token = jwtTokenProvider.generateToken(UUID.randomUUID(), "test@example.com", 3);
        assertThat(jwtTokenProvider.getTokenVersionFromToken(token)).isEqualTo(3);
    }

    @Test
    void validateToken_returnsTrueForValidToken() {
        String token = jwtTokenProvider.generateToken(UUID.randomUUID(), "test@example.com", 0);
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_returnsFalseForTamperedToken() {
        String token = jwtTokenProvider.generateToken(UUID.randomUUID(), "test@example.com", 0);
        assertThat(jwtTokenProvider.validateToken(token + "tampered")).isFalse();
    }

    @Test
    void validateToken_returnsFalseForExpiredToken() {
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationMs", -1000L);
        String token = jwtTokenProvider.generateToken(UUID.randomUUID(), "test@example.com", 0);
        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }

    @Test
    void validateToken_returnsFalseForEmptyString() {
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
    }

    @Test
    void validateToken_returnsFalseForGarbageToken() {
        assertThat(jwtTokenProvider.validateToken("not.a.token")).isFalse();
    }
}

package com.financecoach.backend.security;

import com.financecoach.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private UserRepository userRepository;

    private JwtTokenProvider jwtTokenProvider;
    private JwtAuthenticationFilter filter;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret",
                "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha");
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtExpirationMs", 86400000L);

        filter = new JwtAuthenticationFilter(jwtTokenProvider, userRepository);

        userId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenWithCurrentVersion_authenticates() throws Exception {
        when(userRepository.findTokenVersionById(userId)).thenReturn(Optional.of(2));

        runFilter(jwtTokenProvider.generateToken(userId, "a@b.com", 2));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(userId);
    }

    @Test
    void tokenIssuedBeforePasswordReset_isRejected() throws Exception {
        when(userRepository.findTokenVersionById(userId)).thenReturn(Optional.of(3));

        runFilter(jwtTokenProvider.generateToken(userId, "a@b.com", 2));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tokenForDeletedUser_isRejected() throws Exception {
        when(userRepository.findTokenVersionById(userId)).thenReturn(Optional.empty());

        runFilter(jwtTokenProvider.generateToken(userId, "a@b.com", 0));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void runFilter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}

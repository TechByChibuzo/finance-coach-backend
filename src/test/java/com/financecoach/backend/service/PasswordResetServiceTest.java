package com.financecoach.backend.service;

import com.financecoach.backend.model.PasswordResetToken;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.PasswordResetTokenRepository;
import com.financecoach.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PasswordResetService passwordResetService;

    @Mock private MetricsService metricsService;

    @Test
    void initiatePasswordReset_overTheLimit_quietlySendsNothing() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.countRecentTokensByUserId(org.mockito.ArgumentMatchers.eq(user.getId()),
                org.mockito.ArgumentMatchers.any())).thenReturn(3L);

        // No exception: the response must look the same as for an unknown email
        passwordResetService.initiatePasswordReset("Jane@example.com");

        org.mockito.Mockito.verifyNoInteractions(emailService);
    }

    @Test
    void resetPassword_invalidatesAllExistingLoginTokens() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTokenVersion(4);
        PasswordResetToken token = new PasswordResetToken("reset-token", user.getId(),
                LocalDateTime.now().plusHours(1));
        when(tokenRepository.findByToken("reset-token")).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword123")).thenReturn("new-hash");

        passwordResetService.resetPassword("reset-token", "newPassword123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(5);
    }
}

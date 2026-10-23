package com.financecoach.backend.service;

import com.financecoach.backend.model.PasswordResetToken;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.PasswordResetTokenRepository;
import com.financecoach.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private MetricsService metricsService;

    private PasswordResetService passwordResetService;
    private User user;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetService(tokenRepository, userRepository, emailService, passwordEncoder);
        ReflectionTestUtils.setField(passwordResetService, "metricsService", metricsService);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setFullName("Jane");
        user.setTokenVersion(4);
    }

    // --- request a reset ---

    @Test
    void initiatePasswordReset_issuesAOneHourToken_andEmailsIt() {
        when(userRepository.lookupByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.countRecentTokensByUserId(eq(user.getId()), any())).thenReturn(0L);

        passwordResetService.initiatePasswordReset(" Jane@Example.com");

        ArgumentCaptor<PasswordResetToken> token = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(token.capture());
        verify(tokenRepository).markActiveTokensAsUsed(eq(user.getId()), any());
        assertThat(token.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(token.getValue().getExpiryDate())
                .isBetween(LocalDateTime.now().plusMinutes(59), LocalDateTime.now().plusMinutes(61));
        // Only a hash is stored, so a leaked database can't be used to reset passwords
        ArgumentCaptor<String> emailed = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(eq("jane@example.com"), emailed.capture(), eq("Jane"));
        assertThat(token.getValue().getToken())
                .isEqualTo(sha256(emailed.getValue()))
                .isNotEqualTo(emailed.getValue());
        verify(metricsService).recordPasswordReset();
    }

    @Test
    void initiatePasswordReset_forAnUnknownEmail_quietlyDoesNothing() {
        when(userRepository.lookupByEmail("nobody@example.com")).thenReturn(Optional.empty());

        passwordResetService.initiatePasswordReset("nobody@example.com");

        verifyNoInteractions(tokenRepository, emailService);
    }

    @Test
    void initiatePasswordReset_overTheLimit_quietlySendsNothing() {
        when(userRepository.lookupByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.countRecentTokensByUserId(eq(user.getId()), any())).thenReturn(3L);

        // No exception: the response must look the same as for an unknown email
        passwordResetService.initiatePasswordReset("Jane@example.com");

        verifyNoInteractions(emailService);
        verify(tokenRepository, never()).save(any());
    }

    // --- validate ---

    @Test
    void validateToken_onlyAcceptsUnusedUnexpiredTokens() {
        PasswordResetToken valid = token(LocalDateTime.now().plusMinutes(30));
        PasswordResetToken expired = token(LocalDateTime.now().minusMinutes(1));
        PasswordResetToken used = token(LocalDateTime.now().plusMinutes(30));
        used.setUsed(true);
        when(tokenRepository.findByToken(sha256("valid"))).thenReturn(Optional.of(valid));
        when(tokenRepository.findByToken(sha256("expired"))).thenReturn(Optional.of(expired));
        when(tokenRepository.findByToken(sha256("used"))).thenReturn(Optional.of(used));
        when(tokenRepository.findByToken(sha256("unknown"))).thenReturn(Optional.empty());

        assertThat(passwordResetService.validateToken("valid")).isTrue();
        assertThat(passwordResetService.validateToken("expired")).isFalse();
        assertThat(passwordResetService.validateToken("used")).isFalse();
        assertThat(passwordResetService.validateToken("unknown")).isFalse();
    }

    // --- reset ---

    @Test
    void resetPassword_setsTheNewPassword_usesUpTheToken_andLogsOutEverySession() {
        PasswordResetToken token = token(LocalDateTime.now().plusMinutes(30));
        when(tokenRepository.findByToken(sha256("reset-token"))).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword123")).thenReturn("new-hash");

        passwordResetService.resetPassword("reset-token", "newPassword123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(5);
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(user);
        verify(tokenRepository).save(token);
    }

    @Test
    void resetPassword_rejectsShortPasswords_andBadOrUsedTokens() {
        PasswordResetToken used = token(LocalDateTime.now().plusMinutes(30));
        used.setUsed(true);
        when(tokenRepository.findByToken(sha256("unknown"))).thenReturn(Optional.empty());
        when(tokenRepository.findByToken(sha256("used"))).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> passwordResetService.resetPassword("any", "short"))
                .hasMessageContaining("at least 8");
        assertThatThrownBy(() -> passwordResetService.resetPassword("any", null))
                .hasMessageContaining("at least 8");
        assertThatThrownBy(() -> passwordResetService.resetPassword("unknown", "newPassword123"))
                .hasMessageContaining("Invalid or expired");
        assertThatThrownBy(() -> passwordResetService.resetPassword("used", "newPassword123"))
                .hasMessageContaining("Invalid or expired");
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPassword_forADeletedUser_fails() {
        when(tokenRepository.findByToken(sha256("reset-token"))).thenReturn(Optional.of(token(LocalDateTime.now().plusMinutes(30))));
        when(userRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.resetPassword("reset-token", "newPassword123"))
                .hasMessageContaining("User not found");
    }

    @Test
    void cleanupExpiredTokens_deletesExpiredOnes() {
        passwordResetService.cleanupExpiredTokens();

        verify(tokenRepository).deleteExpiredTokens(any());
    }

    private PasswordResetToken token(LocalDateTime expiry) {
        return new PasswordResetToken(UUID.randomUUID().toString(), user.getId(), expiry);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

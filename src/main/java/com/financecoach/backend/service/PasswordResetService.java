package com.financecoach.backend.service;

import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.PasswordResetToken;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.PasswordResetTokenRepository;
import com.financecoach.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);

    private static final int TOKEN_EXPIRATION_HOURS = 1;
    private static final int RATE_LIMIT_MINUTES = 15;
    private static final int MAX_REQUESTS_PER_PERIOD = 3;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    @Autowired
    private MetricsService metricsService;

    @Autowired
    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                EmailService emailService,
                                PasswordEncoder passwordEncoder) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Initiate password reset process
     */
    @Transactional
    public void initiatePasswordReset(String email) {
        // Find user by email (don't reveal if user exists for security)
        User user = userRepository.lookupByEmail(UserService.normalizeEmail(email)).orElse(null);

        if (user == null) {
            // For security, always return success even if user doesn't exist
            return;
        }

        // Rate limiting: Check recent reset requests
        LocalDateTime rateLimitTime = LocalDateTime.now().minusMinutes(RATE_LIMIT_MINUTES);
        long recentRequests = tokenRepository.countRecentTokensByUserId(user.getId(), rateLimitTime);

        // Over the per-account limit: quietly skip sending. Returning an error here would
        // reveal that the email has an account (unknown emails always "succeed").
        if (recentRequests >= MAX_REQUESTS_PER_PERIOD) {
            logger.warn("Password reset limit reached for user {}, not sending another email", user.getId());
            return;
        }

        // Generate secure token
        String token = UUID.randomUUID().toString();
        LocalDateTime expiryDate = LocalDateTime.now().plusHours(TOKEN_EXPIRATION_HOURS);

//        // Invalidate any existing tokens for this user
//        tokenRepository.deleteByUserId(user.getId());

        // Invalidate any existing active tokens instead of deleting
        tokenRepository.markActiveTokensAsUsed(user.getId(), LocalDateTime.now());

        // Create new token. Only its hash is stored; the token itself is only in the email.
        PasswordResetToken resetToken = new PasswordResetToken(hash(token), user.getId(), expiryDate);
        tokenRepository.save(resetToken);

        // TRACK METRIC
        metricsService.recordPasswordReset();

        // Send email
        emailService.sendPasswordResetEmail(user.getEmail(), token, user.getFullName());
    }

    /**
     * Validate reset token
     */
    public boolean validateToken(String token) {
        PasswordResetToken resetToken = tokenRepository.findByToken(hash(token)).orElse(null);
        return resetToken != null && resetToken.isValid();
    }

    /**
     * Reset password with token
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        // Validate password strength
        if (newPassword == null || newPassword.length() < 8) {
            throw new ValidationException("Password must be at least 8 characters long");
        }

//        if (!newPassword.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$")) {
//            throw new WeakPasswordException("Password must contain upper, lower, number.");
//        }

        // Find and validate token
        PasswordResetToken resetToken = tokenRepository.findByToken(hash(token))
                .orElseThrow(() -> new ValidationException("Invalid or expired reset token"));

        if (!resetToken.isValid()) {
            throw new ValidationException("Invalid or expired reset token");
        }

        // Find user
        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new ValidationException("Invalid or expired reset token"));

        // Update password, and log out every existing session by invalidating issued tokens
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        // Mark token as used
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Clean up expired tokens (should be run periodically)
     */
    @Transactional
    public void cleanupExpiredTokens() {
        tokenRepository.deleteExpiredTokens(LocalDateTime.now());
    }
}
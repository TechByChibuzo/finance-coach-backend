package com.financecoach.userservice.service;

import com.financecoach.userservice.model.PasswordResetToken;
import com.financecoach.userservice.model.User;
import com.financecoach.userservice.repository.PasswordResetTokenRepository;
import com.financecoach.userservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PasswordResetService {

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
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            // For security, always return success even if user doesn't exist
            return;
        }

        // Rate limiting: Check recent reset requests
        LocalDateTime rateLimitTime = LocalDateTime.now().minusMinutes(RATE_LIMIT_MINUTES);
        long recentRequests = tokenRepository.countRecentTokensByUserId(user.getId(), rateLimitTime);

        if (recentRequests >= MAX_REQUESTS_PER_PERIOD) {
            throw new RuntimeException("Too many password reset requests. Please try again in " +
                    RATE_LIMIT_MINUTES + " minutes.");
        }

        // Generate secure token
        String token = UUID.randomUUID().toString();
        LocalDateTime expiryDate = LocalDateTime.now().plusHours(TOKEN_EXPIRATION_HOURS);

//        // Invalidate any existing tokens for this user
//        tokenRepository.deleteByUserId(user.getId());

        // Invalidate any existing active tokens instead of deleting
        tokenRepository.markActiveTokensAsUsed(user.getId(), LocalDateTime.now());

        // Create new token
        PasswordResetToken resetToken = new PasswordResetToken(token, user.getId(), expiryDate);
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
        PasswordResetToken resetToken = tokenRepository.findByToken(token).orElse(null);
        return resetToken != null && resetToken.isValid();
    }

    /**
     * Reset password with token
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        // Validate password strength
        if (newPassword == null || newPassword.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters long");
        }

//        if (!newPassword.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$")) {
//            throw new WeakPasswordException("Password must contain upper, lower, number.");
//        }

        // Find and validate token
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired reset token"));

        if (!resetToken.isValid()) {
            throw new RuntimeException("Invalid or expired reset token");
        }

        // Find user
        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update password
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Mark token as used
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);
    }

    /**
     * Clean up expired tokens (should be run periodically)
     */
    @Transactional
    public void cleanupExpiredTokens() {
        tokenRepository.deleteExpiredTokens(LocalDateTime.now());
    }
}
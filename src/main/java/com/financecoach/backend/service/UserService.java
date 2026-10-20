// src/main/java/com/financecoach/userservice/service/UserService.java
package com.financecoach.backend.service;

import com.financecoach.backend.dto.UserRegistrationRequest;
import com.financecoach.backend.dto.UserResponse;
import com.financecoach.backend.exception.EmailAlreadyExistsException;
import com.financecoach.backend.exception.InvalidCredentialsException;
import com.financecoach.backend.exception.UserNotFoundException;
import com.financecoach.backend.exception.ValidationException;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);


    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    @Autowired
    private MetricsService metricsService;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse registerUser(UserRegistrationRequest request) {
        logger.info("Attempting to register new user with email: {}", request.getEmail());
        // Validate input
        if (request.getEmail() == null || request.getEmail().isEmpty()) {
            logger.error("Registration failed - email is null or empty");
            throw new ValidationException("Email is required");
        }
        if (request.getPassword() == null || request.getPassword().length() < 8) {
            logger.error("Registration failed - password too short");
            throw new ValidationException("Password must be at least 8 characters");
        }

        // Emails are case-insensitive: store one canonical form
        String email = normalizeEmail(request.getEmail());

        // Check if email already exists
        if (userRepository.existsByEmailIgnoreCase(email)) {
            logger.warn("Registration failed - email already exists: {}", email);
            throw new EmailAlreadyExistsException(email);
        }

        // Create new user with hashed password
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword())); // Hash password!
        user.setFullName(request.getFullName());
        user.setCreatedAt(LocalDateTime.now());

        // Save to database
        User savedUser = userRepository.save(user);
        logger.info("User registered successfully - ID: {}, Email: {}",
                savedUser.getId(), savedUser.getEmail());

        // TRACK METRIC
        metricsService.recordUserRegistration();
        return convertToResponse(savedUser);
    }

    public User authenticate(String email, String password) {
        logger.debug("Authentication attempt for email: {}", email);

        // Find user by email
        User user = userRepository.lookupByEmail(normalizeEmail(email))
                .orElseThrow(InvalidCredentialsException::new);

        // Check password
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            logger.warn("Authentication failed - invalid password for: {}", email);
            throw new InvalidCredentialsException();
        }

        // Update only last_login. Saving the whole user would write back the password hash and
        // token version loaded above, undoing a password reset that committed in the meantime.
        LocalDateTime now = LocalDateTime.now();
        userRepository.updateLastLogin(user.getId(), now);
        user.setLastLogin(now);

        logger.info("User authenticated successfully - ID: {}, Email: {}",
                user.getId(), email);

        metricsService.recordUserLogin();
        return user;
    }

    /**
     * Get user by ID
     * Throws UserNotFoundException if not found
     */
    public UserResponse getUserById(UUID id) {
        logger.debug("Fetching user by ID: {}", id);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return convertToResponse(user);
    }

    /**
     * Get user by email
     * Throws UserNotFoundException if not found
     */
    public UserResponse getUserByEmail(String email) {
        User user = userRepository.lookupByEmail(normalizeEmail(email))
                .orElseThrow(() -> new UserNotFoundException(email));
        return convertToResponse(user);
    }

    /**
     * Canonical form of an email address: trimmed and lower-cased, so "Foo@X.com " and
     * "foo@x.com" are the same account.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    // Helper method to convert Entity to DTO
    private UserResponse convertToResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCreatedAt()
        );
    }
}

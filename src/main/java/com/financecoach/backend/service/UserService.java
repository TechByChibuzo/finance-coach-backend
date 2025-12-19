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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

        // Check if email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            logger.warn("Registration failed - email already exists: {}", request.getEmail());
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        // Create new user with hashed password
        User user = new User();
        user.setEmail(request.getEmail());
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
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        // Check password
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            logger.warn("Authentication failed - invalid password for: {}", email);
            throw new InvalidCredentialsException();
        }

        // Update last login
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

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
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
        return convertToResponse(user);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Delete user
     * Throws UserNotFoundException if not found
     */
    public void deleteUser(UUID id) {
        logger.info("Attempting to delete user: {}", id);
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException(id);
        }
        userRepository.deleteById(id);
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
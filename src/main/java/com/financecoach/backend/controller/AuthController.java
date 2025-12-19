// src/main/java/com/financecoach/backend/controller/AuthController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.*;
import com.financecoach.backend.model.User;
import com.financecoach.backend.security.JwtTokenProvider;
import com.financecoach.backend.service.PasswordResetService;
import com.financecoach.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordResetService passwordResetService;

    @Autowired
    public AuthController(UserService userService,
                          JwtTokenProvider jwtTokenProvider,
                          PasswordResetService passwordResetService) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordResetService = passwordResetService;
    }

    /**
     * Register a new user
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody UserRegistrationRequest request) {
        // Service will throw exceptions if validation fails
        UserResponse user = userService.registerUser(request);

        // Generate JWT token
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());

        // Return token + user info
        LoginResponse response = new LoginResponse(token, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Login with email and password
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // Service will throw InvalidCredentialsException if auth fails
        User user = userService.authenticate(request.getEmail(), request.getPassword());

        // Generate JWT token
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());

        // Create response
        UserResponse userResponse = new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCreatedAt()
        );

        LoginResponse response = new LoginResponse(token, userResponse);
        return ResponseEntity.ok(response);
    }

    /**
     * Request password reset
     * POST /api/auth/forgot-password
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        // Service handles rate limiting and email sending
        // Always returns success to prevent email enumeration
        passwordResetService.initiatePasswordReset(request.getEmail());

        return ResponseEntity.ok(new MessageResponse(
                "If an account exists with that email, you will receive password reset instructions."));
    }

    /**
     * Reset password with token
     * POST /api/auth/reset-password
     */
    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {

        // Service will throw exception if token is invalid/expired
        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());

        return ResponseEntity.ok(new MessageResponse("Password has been reset successfully!"));
    }

    /**
     * Validate reset token
     * GET /api/auth/validate-reset-token/{token}
     */
    @GetMapping("/validate-reset-token/{token}")
    public ResponseEntity<MessageResponse> validateResetToken(@PathVariable String token) {
        boolean isValid = passwordResetService.validateToken(token);

        if (isValid) {
            return ResponseEntity.ok(new MessageResponse("Token is valid"));
        }

        // Return 400 if invalid - or let service throw exception
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponse("Invalid or expired token"));
    }
}
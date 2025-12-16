// src/main/java/com/financecoach/userservice/controller/AuthController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.*;
import com.financecoach.backend.model.User;
import com.financecoach.backend.security.JwtTokenProvider;
import com.financecoach.backend.service.PasswordResetService;
import com.financecoach.backend.service.UserService;
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
                          JwtTokenProvider jwtTokenProvider, PasswordResetService passwordResetService) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody UserRegistrationRequest request) {
        try {
            UserResponse user = userService.registerUser(request);

            // Generate token for the new user
            String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());

            LoginResponse response = new LoginResponse(token, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            // Authenticate user
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

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        try {
            passwordResetService.initiatePasswordReset(request.getEmail());
            return ResponseEntity.ok(new MessageResponse(
                    "If an account exists with that email, you will receive password reset instructions."));
        } catch (RuntimeException e) {
            // Don't reveal if rate limit was hit
            return ResponseEntity.ok(new MessageResponse(
                    "If an account exists with that email, you will receive password reset instructions."));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
            return ResponseEntity.ok(new MessageResponse("Password has been reset successfully!"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/validate-reset-token/{token}")
    public ResponseEntity<?> validateResetToken(@PathVariable String token) {
        boolean isValid = passwordResetService.validateToken(token);
        if (isValid) {
            return ResponseEntity.ok(new MessageResponse("Token is valid"));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Invalid or expired token"));
        }
    }
}
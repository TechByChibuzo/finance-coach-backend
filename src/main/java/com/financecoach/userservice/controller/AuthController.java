// src/main/java/com/financecoach/userservice/controller/AuthController.java
package com.financecoach.userservice.controller;

import com.financecoach.userservice.dto.*;
import com.financecoach.userservice.model.User;
import com.financecoach.userservice.security.JwtTokenProvider;
import com.financecoach.userservice.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;

    @Autowired
    public AuthController(UserService userService, JwtTokenProvider jwtTokenProvider) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
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
}
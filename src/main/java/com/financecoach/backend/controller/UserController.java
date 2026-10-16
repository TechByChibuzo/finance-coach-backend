// src/main/java/com/financecoach/backend/controller/UserController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.UserResponse;
import com.financecoach.backend.service.AccountDeletionService;
import com.financecoach.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final AccountDeletionService accountDeletionService;

    @Autowired
    public UserController(UserService userService, AccountDeletionService accountDeletionService) {
        this.userService = userService;
        this.accountDeletionService = accountDeletionService;
    }

    /**
     * Health check endpoint
     * GET /api/users/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("User Service is running!");
    }

    /**
     * Get current user's profile
     * GET /api/users/me
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        UUID userId = getCurrentUserId();
        UserResponse user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    /**
     * Delete current user and all of their data
     * DELETE /api/users/me
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser() {
        UUID userId = getCurrentUserId();
        accountDeletionService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }

    // Helper method to get current authenticated user ID
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (UUID) authentication.getPrincipal();
    }
}
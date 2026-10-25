// src/main/java/com/financecoach/backend/controller/UserController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.UserResponse;
import com.financecoach.backend.security.CurrentUser;
import com.financecoach.backend.service.AccountDeletionService;
import com.financecoach.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
        UUID userId = CurrentUser.id();
        UserResponse user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    /**
     * Email preferences
     * PATCH /api/users/me/preferences
     */
    @PatchMapping("/me/preferences")
    public ResponseEntity<UserResponse> updatePreferences(@RequestBody PreferencesRequest request) {
        UUID userId = CurrentUser.id();
        UserResponse user = request.weeklySummaryEmails() == null
                ? userService.getUserById(userId)
                : userService.updateWeeklySummaryEmails(userId, request.weeklySummaryEmails());
        return ResponseEntity.ok(user);
    }

    public record PreferencesRequest(Boolean weeklySummaryEmails) {
    }

    /**
     * Delete current user and all of their data
     * DELETE /api/users/me
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser() {
        UUID userId = CurrentUser.id();
        accountDeletionService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }

}
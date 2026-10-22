// src/main/java/com/financecoach/userservice/dto/UserResponse.java
package com.financecoach.backend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public class UserResponse {
    private UUID id;
    private String email;
    private String fullName;
    private boolean weeklySummaryEmails = true;
    private LocalDateTime createdAt;

    // Constructors
    public UserResponse() {}

    public UserResponse(UUID id, String email, String fullName, LocalDateTime createdAt) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.createdAt = createdAt;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public boolean isWeeklySummaryEmails() {
        return weeklySummaryEmails;
    }

    public void setWeeklySummaryEmails(boolean weeklySummaryEmails) {
        this.weeklySummaryEmails = weeklySummaryEmails;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
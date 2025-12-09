// src/main/java/com/financecoach/userservice/dto/UserRegistrationRequest.java
package com.financecoach.userservice.dto;

public class UserRegistrationRequest {
    private String email;
    private String password;
    private String fullName;

    // Constructors
    public UserRegistrationRequest() {}

    public UserRegistrationRequest(String email, String password, String fullName) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
    }

    // Getters and Setters
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
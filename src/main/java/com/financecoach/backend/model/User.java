// src/main/java/com/financecoach/userservice/model/User.java
package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
public class User {

    public static final int INITIAL_TOKEN_VERSION = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Embedded in every JWT. Incrementing it invalidates all tokens issued before (e.g. on password reset)
    @Column(name = "token_version", nullable = false)
    @ColumnDefault("0")
    private Integer tokenVersion = INITIAL_TOKEN_VERSION;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    // Constructors
    public User() {
        this.createdAt = LocalDateTime.now();
    }

    public User(String email, String passwordHash, String fullName) {
        this();
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }
}
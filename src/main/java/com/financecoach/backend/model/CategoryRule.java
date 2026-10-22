package com.financecoach.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * "Always put this merchant in this category": applied to the user's existing transactions
 * when created, and to new ones as they are synced or imported.
 */
@Entity
@Table(name = "category_rules",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "match_key"}))
@Data
public class CategoryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Lower-cased merchant name (or description) the rule matches exactly
    @Column(name = "match_key", nullable = false, length = 300)
    private String matchKey;

    // The merchant name as the user saw it, for listing rules
    @Column(name = "display_name", length = 300)
    private String displayName;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "subcategory", length = 128)
    private String subcategory;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

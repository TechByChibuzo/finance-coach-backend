package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "feature_flags")
@Data
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String featureName;

    @Column(nullable = false)
    private Boolean isEnabled = false;

    @Column(length = 50)
    private String requiredPlan; // FREE, PREMIUM, PRO

    @Column(nullable = false)
    private Integer rolloutPercentage = 0; // 0-100

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();
}

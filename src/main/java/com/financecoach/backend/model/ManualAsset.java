package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "manual_assets",
        indexes = {
                @Index(name = "idx_manual_assets_user", columnList = "user_id"),
                @Index(name = "idx_manual_assets_type", columnList = "type")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;  // "My House", "2020 Toyota Camry"

    @Column(length = 50, nullable = false)
    private String type;  // real_estate, vehicle, jewelry, other

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal value;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
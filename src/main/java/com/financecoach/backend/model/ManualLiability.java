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
        name = "manual_liabilities",
        indexes = {
                @Index(name = "idx_manual_liabilities_user", columnList = "user_id"),
                @Index(name = "idx_manual_liabilities_type", columnList = "type")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualLiability {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;  // "Mortgage", "Student Loan"

    @Column(length = 50, nullable = false)
    private String type;  // mortgage, auto_loan, student_loan, personal_loan, other

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;  // Current balance owed

    @Column(name = "interest_rate", precision = 5, scale = 2)
    private BigDecimal interestRate;  // 4.5% APR

    @Column(name = "monthly_payment", precision = 18, scale = 2)
    private BigDecimal monthlyPayment;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
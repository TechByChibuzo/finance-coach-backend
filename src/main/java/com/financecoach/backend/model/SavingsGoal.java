package com.financecoach.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Something the user is saving towards. Progress is the balance of a linked savings account,
 * or, without one, an amount the user keeps up to date themselves.
 */
@Entity
@Table(name = "savings_goals")
@Data
public class SavingsGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "target_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal targetAmount;

    // Optional date to reach the target by
    @Column(name = "target_date")
    private LocalDate targetDate;

    // A bank account whose balance is the progress; null to track it by hand
    @Column(name = "account_id")
    private UUID accountId;

    // Progress tracked by hand, for goals without a linked account
    @Column(name = "saved_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal savedAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

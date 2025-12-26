package com.financecoach.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavingsGoalRequest {

    @NotNull(message = "Savings goal is required")
    @Positive(message = "Savings goal must be positive")
    private BigDecimal savingsGoal;
}
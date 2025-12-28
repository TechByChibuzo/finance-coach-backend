package com.financecoach.backend.dto.networth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ManualLiabilityRequest {
    @NotBlank(message = "Liability name is required")
    private String name;

    @NotBlank(message = "Liability type is required")
    private String type;  // mortgage, auto_loan, student_loan, personal_loan, other

    @NotNull(message = "Balance is required")
    @Positive(message = "Balance must be positive")
    private BigDecimal balance;

    @PositiveOrZero(message = "Interest rate must be zero or positive")
    private BigDecimal interestRate;

    @PositiveOrZero(message = "Monthly payment must be zero or positive")
    private BigDecimal monthlyPayment;

    private String notes;
}
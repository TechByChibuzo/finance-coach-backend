package com.financecoach.backend.dto.networth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualLiabilityDTO {
    private UUID id;
    private String name;
    private String type;
    private BigDecimal balance;
    private BigDecimal interestRate;
    private BigDecimal monthlyPayment;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
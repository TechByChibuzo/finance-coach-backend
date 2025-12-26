package com.financecoach.backend.dto.investment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSnapshotDTO {
    private LocalDate date;
    private BigDecimal totalValue;
    private BigDecimal totalGainLoss;
    private BigDecimal gainLossPercentage;
}
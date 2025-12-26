package com.financecoach.backend.dto.investment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSummaryDTO {
    private BigDecimal totalValue;
    private BigDecimal totalCostBasis;
    private BigDecimal totalGainLoss;
    private BigDecimal gainLossPercentage;
    private List<HoldingDTO> holdings;
    private Map<String, BigDecimal> allocationBreakdown;
}
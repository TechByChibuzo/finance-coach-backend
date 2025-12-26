package com.financecoach.backend.dto.networth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetWorthSummaryDTO {
    private BigDecimal cashBalance;
    private BigDecimal investmentsValue;
    private BigDecimal manualAssetsValue;
    private BigDecimal totalAssets;
    private BigDecimal creditCardDebt;
    private BigDecimal manualLiabilities;
    private BigDecimal totalLiabilities;
    private BigDecimal netWorth;
}
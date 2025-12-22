package com.financecoach.backend.dto.investment;

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
public class HoldingDTO {
    private UUID id;
    private UUID accountId;
    private String accountName;
    private String symbol;
    private String name;
    private String type;
    private BigDecimal quantity;
    private BigDecimal currentPrice;
    private BigDecimal currentValue;
    private BigDecimal costBasis;
    private BigDecimal gainLoss;
    private BigDecimal gainLossPercentage;
    private String currency;
    private LocalDateTime lastUpdated;
}
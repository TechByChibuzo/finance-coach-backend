package com.financecoach.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
public class PlanResponse {
    private UUID id;
    private String name;
    private String displayName;
    private String description;
    private BigDecimal priceMonthly;
    private BigDecimal priceYearly;
    private Map<String, Object> features;
    private Map<String, Object> limits;
}
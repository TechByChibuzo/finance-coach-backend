package com.financecoach.backend.dto.networth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualAssetResponse {

    private UUID id;
    private String name;
    private String type;
    private BigDecimal value;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
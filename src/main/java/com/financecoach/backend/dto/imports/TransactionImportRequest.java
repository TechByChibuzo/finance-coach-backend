package com.financecoach.backend.dto.imports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Transactions from a bank's CSV export, already parsed in the browser.
 * Amounts follow Plaid's convention: positive = money out, negative = money in.
 */
public record TransactionImportRequest(
        @NotBlank @Size(max = 60) String accountName,
        @NotEmpty @Size(max = 5000) List<@Valid Row> rows
) {
    public record Row(
            @NotNull @PastOrPresent LocalDate date,
            @NotBlank @Size(max = 300) String description,
            @NotNull @DecimalMin("-10000000") @DecimalMax("10000000") BigDecimal amount
    ) {
    }
}

package com.financecoach.backend.dto;

import jakarta.validation.constraints.Size;

/**
 * Changes to a transaction's note and whether it's hidden from totals. A null field is left as it is.
 */
public record TransactionUpdateRequest(@Size(max = 500) String notes, Boolean hidden) {
}

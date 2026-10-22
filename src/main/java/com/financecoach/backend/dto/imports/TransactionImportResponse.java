package com.financecoach.backend.dto.imports;

import java.util.UUID;

/**
 * Outcome of a CSV import. batchId undoes it; duplicates were already imported before.
 */
public record TransactionImportResponse(UUID batchId, int imported, int duplicates) {
}

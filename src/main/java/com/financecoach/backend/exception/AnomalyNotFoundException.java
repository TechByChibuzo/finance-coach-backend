package com.financecoach.backend.exception;

import java.util.UUID;

public class AnomalyNotFoundException extends FinanceCoachException {

    public AnomalyNotFoundException(UUID anomalyId) {
        super("Anomaly not found with id: " + anomalyId, "ANOMALY_NOT_FOUND");
    }
}

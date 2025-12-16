// src/main/java/com/financecoach/userservice/service/MetricsService.java
package com.financecoach.userservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Custom metrics service for tracking business operations
 * All metrics are automatically exported to Prometheus
 */
@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;

    // Counters
    private final Counter transactionsSynced;
    private final Counter budgetsCreated;
    private final Counter budgetsExceeded;
    private final Counter aiCoachRequests;
    private final Counter userRegistrations;
    private final Counter userLogins;
    private final Counter bankAccountsConnected;
    private final Counter passwordResets;

    // Timers
    private final Timer transactionSyncTimer;
    private final Timer aiCoachResponseTimer;
    private final Timer plaidApiTimer;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Initialize counters
        this.transactionsSynced = Counter.builder("finance_coach.transactions.synced")
                .description("Total number of transactions synced from Plaid")
                .tag("type", "sync")
                .register(meterRegistry);

        this.budgetsCreated = Counter.builder("finance_coach.budgets.created")
                .description("Total number of budgets created by users")
                .tag("type", "create")
                .register(meterRegistry);

        this.budgetsExceeded = Counter.builder("finance_coach.budgets.exceeded")
                .description("Total number of budgets that were exceeded")
                .tag("type", "alert")
                .register(meterRegistry);

        this.aiCoachRequests = Counter.builder("finance_coach.ai_coach.requests")
                .description("Total number of AI coach requests")
                .tag("type", "ai")
                .register(meterRegistry);

        this.userRegistrations = Counter.builder("finance_coach.users.registrations")
                .description("Total number of user registrations")
                .tag("type", "auth")
                .register(meterRegistry);

        this.userLogins = Counter.builder("finance_coach.users.logins")
                .description("Total number of successful user logins")
                .tag("type", "auth")
                .register(meterRegistry);

        this.bankAccountsConnected = Counter.builder("finance_coach.bank_accounts.connected")
                .description("Total number of bank accounts connected")
                .tag("type", "plaid")
                .register(meterRegistry);

        this.passwordResets = Counter.builder("finance_coach.users.password_resets")
                .description("Total number of password reset requests")
                .tag("type", "auth")
                .register(meterRegistry);

        // Initialize timers
        this.transactionSyncTimer = Timer.builder("finance_coach.transactions.sync.duration")
                .description("Time taken to sync transactions")
                .tag("operation", "sync")
                .register(meterRegistry);

        this.aiCoachResponseTimer = Timer.builder("finance_coach.ai_coach.response.duration")
                .description("Time taken for AI coach to respond")
                .tag("operation", "ai_response")
                .register(meterRegistry);

        this.plaidApiTimer = Timer.builder("finance_coach.plaid.api.duration")
                .description("Time taken for Plaid API calls")
                .tag("operation", "plaid_api")
                .register(meterRegistry);
    }

    // ===== COUNTER METHODS =====

    public void recordTransactionsSynced(int count) {
        transactionsSynced.increment(count);
    }

    public void recordBudgetCreated() {
        budgetsCreated.increment();
    }

    public void recordBudgetExceeded() {
        budgetsExceeded.increment();
    }

    public void recordAiCoachRequest() {
        aiCoachRequests.increment();
    }

    public void recordUserRegistration() {
        userRegistrations.increment();
    }

    public void recordUserLogin() {
        userLogins.increment();
    }

    public void recordBankAccountConnected() {
        bankAccountsConnected.increment();
    }

    public void recordPasswordReset() {
        passwordResets.increment();
    }

    // ===== TIMER METHODS =====

    public void recordTransactionSyncDuration(long durationMs) {
        transactionSyncTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordAiCoachResponseDuration(long durationMs) {
        aiCoachResponseTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordPlaidApiDuration(long durationMs) {
        plaidApiTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ===== GAUGE METHODS (for current values) =====

    public void recordActiveBudgets(int count) {
        meterRegistry.gauge("finance_coach.budgets.active", count);
    }

    public void recordActiveUsers(int count) {
        meterRegistry.gauge("finance_coach.users.active", count);
    }

    public void recordTotalTransactions(long count) {
        meterRegistry.gauge("finance_coach.transactions.total", count);
    }

    // ===== UTILITY: Execute with timing =====

    public <T> T timeOperation(String operationName, java.util.function.Supplier<T> operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return operation.get();
        } finally {
            sample.stop(Timer.builder("finance_coach.operation.duration")
                    .tag("operation", operationName)
                    .register(meterRegistry));
        }
    }

    public void timeOperation(String operationName, Runnable operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            operation.run();
        } finally {
            sample.stop(Timer.builder("finance_coach.operation.duration")
                    .tag("operation", operationName)
                    .register(meterRegistry));
        }
    }
}
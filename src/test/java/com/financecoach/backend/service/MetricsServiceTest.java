package com.financecoach.backend.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsServiceTest {

    private SimpleMeterRegistry registry;
    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new MetricsService(registry);
    }

    @Test
    void counters_countBusinessEvents() {
        metricsService.recordTransactionsSynced(25);
        metricsService.recordBudgetCreated();
        metricsService.recordBudgetExceeded();
        metricsService.recordAiCoachRequest();
        metricsService.recordAiCoachRequest();
        metricsService.recordUserRegistration();
        metricsService.recordUserLogin();
        metricsService.recordBankAccountConnected();
        metricsService.recordPasswordReset();

        assertThat(count("finance_coach.transactions.synced")).isEqualTo(25);
        assertThat(count("finance_coach.budgets.created")).isEqualTo(1);
        assertThat(count("finance_coach.budgets.exceeded")).isEqualTo(1);
        assertThat(count("finance_coach.ai_coach.requests")).isEqualTo(2);
        assertThat(count("finance_coach.users.registrations")).isEqualTo(1);
        assertThat(count("finance_coach.users.logins")).isEqualTo(1);
        assertThat(count("finance_coach.bank_accounts.connected")).isEqualTo(1);
        assertThat(count("finance_coach.users.password_resets")).isEqualTo(1);
    }

    @Test
    void timers_recordDurations() {
        metricsService.recordTransactionSyncDuration(1200);
        metricsService.recordAiCoachResponseDuration(3400);
        metricsService.recordPlaidApiDuration(250);

        assertThat(registry.find("finance_coach.transactions.sync.duration").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(1200);
        assertThat(registry.find("finance_coach.ai_coach.response.duration").timer().count()).isEqualTo(1);
        assertThat(registry.find("finance_coach.plaid.api.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void gauges_andTimedOperations_work() {
        metricsService.recordActiveBudgets(3);
        metricsService.recordActiveUsers(10);
        metricsService.recordTotalTransactions(500L);

        String result = metricsService.timeOperation("lookup", () -> "done");
        AtomicBoolean ran = new AtomicBoolean();
        metricsService.timeOperation("job", () -> ran.set(true));

        assertThat(result).isEqualTo("done");
        assertThat(ran).isTrue();
        assertThat(registry.find("finance_coach.operation.duration").tag("operation", "lookup").timer().count())
                .isEqualTo(1);
        assertThat(registry.find("finance_coach.operation.duration").tag("operation", "job").timer().count())
                .isEqualTo(1);
    }

    private double count(String name) {
        return registry.find(name).counter().count();
    }
}

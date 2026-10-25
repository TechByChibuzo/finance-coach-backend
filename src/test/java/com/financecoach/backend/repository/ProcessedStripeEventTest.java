package com.financecoach.backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record that makes Stripe webhooks idempotent: an event ID is marked processed once.
 */
class ProcessedStripeEventTest extends PostgresRepositoryTest {

    @Autowired private ProcessedStripeEventRepository repository;

    @Test
    void anEventIsMarkedOnce_andAgainItsAlreadyProcessed() {
        assertThat(repository.markProcessed("evt_123", "invoice.payment_succeeded")).isEqualTo(1);
        assertThat(repository.markProcessed("evt_123", "invoice.payment_succeeded")).isZero();
        assertThat(repository.markProcessed("evt_456", "invoice.payment_succeeded")).isEqualTo(1);
    }

    @Test
    void oldRecordsCanBeDeleted() {
        repository.markProcessed("evt_old", "customer.subscription.updated");
        jdbcTemplate.update("UPDATE processed_stripe_events SET processed_at = ? WHERE event_id = 'evt_old'",
                LocalDateTime.now().minusDays(40));
        repository.markProcessed("evt_new", "customer.subscription.updated");

        repository.deleteByProcessedAtBefore(LocalDateTime.now().minusDays(30));

        assertThat(jdbcTemplate.queryForList("SELECT event_id FROM processed_stripe_events", String.class))
                .containsExactly("evt_new");
    }
}

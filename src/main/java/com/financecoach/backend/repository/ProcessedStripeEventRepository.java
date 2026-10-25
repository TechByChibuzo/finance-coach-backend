package com.financecoach.backend.repository;

import com.financecoach.backend.model.ProcessedStripeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {

    /**
     * Record an event as processed. Runs inside the webhook's transaction, so a failed event
     * rolls this back and Stripe's retry processes it again; a second delivery arriving at the same
     * time waits for the first and then finds it recorded.
     *
     * @return 1 if this is the first time, 0 if the event was already processed
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_stripe_events (event_id, event_type, processed_at)
            VALUES (:eventId, :eventType, NOW())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int markProcessed(@Param("eventId") String eventId, @Param("eventType") String eventType);

    @Transactional
    @Modifying
    @Query("DELETE FROM ProcessedStripeEvent e WHERE e.processedAt < :cutoff")
    int deleteByProcessedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}

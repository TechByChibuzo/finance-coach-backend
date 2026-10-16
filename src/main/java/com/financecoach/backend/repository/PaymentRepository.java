package com.financecoach.backend.repository;

import com.financecoach.backend.model.Payment;
import com.financecoach.backend.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Find all payments for a user
     */
    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Find payments by status
     */
    List<Payment> findByStatus(PaymentStatus status);

    /**
     * Find payments for a subscription
     */
    List<Payment> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    /**
     * Find by Stripe payment intent ID
     */
    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

    /**
     * Find recent payments
     */
    @Query("SELECT p FROM Payment p WHERE p.createdAt >= :since ORDER BY p.createdAt DESC")
    List<Payment> findRecentPayments(@Param("since") LocalDateTime since);

    /**
     * Calculate total revenue
     */
    @Query("SELECT SUM(p.amount) FROM Payment p WHERE p.status = 'SUCCEEDED'")
    BigDecimal calculateTotalRevenue();

    /**
     * Calculate revenue for date range
     */
    @Query("SELECT SUM(p.amount) FROM Payment p WHERE p.status = 'SUCCEEDED' " +
            "AND p.paidAt BETWEEN :startDate AND :endDate")
    BigDecimal calculateRevenueForPeriod(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    /**
     * Find failed payments for a user
     */
    List<Payment> findByUserIdAndStatus(UUID userId, PaymentStatus status);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM Payment p WHERE p.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

// src/main/java/com/financecoach/userservice/repository/UserRepository.java
package com.financecoach.backend.repository;

import com.financecoach.backend.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // Spring Data JPA automatically implements these methods!
    // Case-insensitive so accounts created before emails were normalized still match
    List<User> findAllByEmailIgnoreCase(String email);

    /**
     * Find the account for a normalized email without ever throwing. Accounts created before
     * emails were normalized can differ only in case ("Jane@x.com" and "jane@x.com"). Prefer
     * the exact match; if it is still ambiguous, treat it as not found. A 500 here would make
     * the forgot-password response differ for some emails and reveal that they're registered.
     */
    default Optional<User> lookupByEmail(String normalizedEmail) {
        List<User> matches = findAllByEmailIgnoreCase(normalizedEmail);
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }

        List<User> exact = matches.stream()
                .filter(u -> normalizedEmail.equals(u.getEmail()))
                .toList();
        return exact.size() == 1 ? Optional.of(exact.get(0)) : Optional.empty();
    }

    Optional<User> findByStripeCustomerId(String stripeCustomerId);

    boolean existsByEmailIgnoreCase(String email);

    // Touches only last_login, so it can't overwrite credentials changed concurrently
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.lastLogin = :lastLogin WHERE u.id = :id")
    int updateLastLogin(@Param("id") UUID id, @Param("lastLogin") LocalDateTime lastLogin);

    // Touches only the preference, like updateLastLogin
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.weeklySummaryEmails = :enabled WHERE u.id = :id")
    int updateWeeklySummaryEmails(@Param("id") UUID id, @Param("enabled") boolean enabled);

    // Claims the user's summary for a week: 1 for the one caller that may send it, 0 if it was
    // already sent (e.g. by a second instance running during a deploy)
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.weeklySummarySentFor = :week WHERE u.id = :id "
            + "AND (u.weeklySummarySentFor IS NULL OR u.weeklySummarySentFor < :week)")
    int claimWeeklySummary(@Param("id") UUID id, @Param("week") java.time.LocalDate week);

    // Checked on every authenticated request, so only load the one column
    @Query("SELECT u.tokenVersion FROM User u WHERE u.id = :id")
    Optional<Integer> findTokenVersionById(@Param("id") UUID id);

    // Row lock that serialises per-user checks such as plan limits (call inside a transaction)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockById(@Param("id") UUID id);
}
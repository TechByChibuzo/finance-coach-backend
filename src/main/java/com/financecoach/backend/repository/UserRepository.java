// src/main/java/com/financecoach/userservice/repository/UserRepository.java
package com.financecoach.backend.repository;

import com.financecoach.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // Spring Data JPA automatically implements these methods!
    // Case-insensitive so accounts created before emails were normalized still match
    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByStripeCustomerId(String stripeCustomerId);

    boolean existsByEmailIgnoreCase(String email);

    // Checked on every authenticated request, so only load the one column
    @Query("SELECT u.tokenVersion FROM User u WHERE u.id = :id")
    Optional<Integer> findTokenVersionById(@Param("id") UUID id);
}
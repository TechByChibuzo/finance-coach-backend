// src/main/java/com/financecoach/userservice/repository/UserRepository.java
package com.financecoach.backend.repository;

import com.financecoach.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // Spring Data JPA automatically implements these methods!
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
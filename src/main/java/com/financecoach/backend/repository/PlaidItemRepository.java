package com.financecoach.backend.repository;

import com.financecoach.backend.model.PlaidItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlaidItemRepository extends JpaRepository<PlaidItem, UUID> {

    Optional<PlaidItem> findByPlaidItemId(String plaidItemId);

    List<PlaidItem> findByUserId(UUID userId);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM PlaidItem p WHERE p.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

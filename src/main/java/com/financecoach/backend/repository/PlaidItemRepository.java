package com.financecoach.backend.repository;

import com.financecoach.backend.model.PlaidItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlaidItemRepository extends JpaRepository<PlaidItem, UUID> {

    Optional<PlaidItem> findByPlaidItemId(String plaidItemId);
}

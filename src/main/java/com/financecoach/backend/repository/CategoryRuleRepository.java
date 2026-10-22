package com.financecoach.backend.repository;

import com.financecoach.backend.model.CategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    List<CategoryRule> findByUserIdOrderByDisplayNameAsc(UUID userId);

    Optional<CategoryRule> findByUserIdAndMatchKey(UUID userId, String matchKey);

    @Transactional
    void deleteByUserId(UUID userId);
}

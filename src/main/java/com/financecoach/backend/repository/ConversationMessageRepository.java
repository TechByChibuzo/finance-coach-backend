package com.financecoach.backend.repository;

import com.financecoach.backend.model.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    List<ConversationMessage> findByUserIdAndSessionIdOrderByCreatedAtAsc(
            UUID userId, String sessionId
    );

    List<ConversationMessage> findByUserIdOrderByCreatedAtDesc(UUID userId);

    void deleteByUserIdAndSessionId(UUID userId, String sessionId);

    long countByUserIdAndSessionId(UUID userId, String sessionId);

    // Account deletion: remove all of the user's rows in one statement
    @Modifying
    @Query("DELETE FROM ConversationMessage c WHERE c.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}

package com.financecoach.backend.repository;

import com.financecoach.backend.model.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
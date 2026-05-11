package com.financecoach.backend.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "conversation_messages", indexes = {
        @Index(name = "idx_conversation_user_session", columnList = "user_id, session_id"),
        @Index(name = "idx_conversation_created_at", columnList = "created_at")
})
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "user_message", columnDefinition = "TEXT", nullable = false)
    private String userMessage;

    @Column(name = "assistant_message", columnDefinition = "TEXT", nullable = false)
    private String assistantMessage;

    @Column(name = "created_at")
    private final LocalDateTime createdAt = LocalDateTime.now();

    public ConversationMessage() {}

    public ConversationMessage(UUID userId, String sessionId,
                               String userMessage, String assistantMessage) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
    }
}
package com.financecoach.backend.service;

import com.financecoach.backend.model.ConversationMessage;
import com.financecoach.backend.repository.ConversationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationMessageRepository conversationRepository;

    public ConversationService(ConversationMessageRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    public void saveMessage(UUID userId, String sessionId,
                            String userMessage, String assistantMessage) {
        ConversationMessage message = new ConversationMessage(
                userId, sessionId, userMessage, assistantMessage
        );
        conversationRepository.save(message);
        logger.debug("Saved conversation turn for user {} session {}", userId, sessionId);
    }

    public List<ClaudeService.ConversationTurn> getHistory(UUID userId, String sessionId) {
        // Only the last 10 turns (to fit Claude's context window), fetched by the
        // query instead of loading the whole session; reversed back to oldest first
        List<ConversationMessage> recentMessages = new ArrayList<>(conversationRepository
                .findTop10ByUserIdAndSessionIdOrderByCreatedAtDesc(userId, sessionId));
        Collections.reverse(recentMessages);

        return recentMessages.stream()
                .map(m -> new ClaudeService.ConversationTurn(
                        m.getUserMessage(),
                        m.getAssistantMessage()
                ))
                .toList();
    }

    /**
     * The latest turns of a conversation, oldest first, for showing it again in the app
     */
    public List<ConversationMessage> getTranscript(UUID userId, String sessionId, int maxTurns) {
        List<ConversationMessage> messages = conversationRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
        return messages.subList(Math.max(0, messages.size() - maxTurns), messages.size());
    }

    // Derived deleteBy... queries load and remove entities, which needs an active transaction
    @Transactional
    public void clearSession(UUID userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
        logger.debug("Cleared session {} for user {}", sessionId, userId);
    }

    public long getSessionMessageCount(UUID userId, String sessionId) {
        return conversationRepository.countByUserIdAndSessionId(userId, sessionId);
    }
}
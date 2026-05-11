package com.financecoach.backend.service;

import com.financecoach.backend.model.ConversationMessage;
import com.financecoach.backend.repository.ConversationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);
    private static final int MAX_HISTORY_TURNS = 10;

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
        List<ConversationMessage> messages = conversationRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);

        // Keep only the last N turns to avoid exceeding Claude's context window
        int fromIndex = Math.max(0, messages.size() - MAX_HISTORY_TURNS);
        List<ConversationMessage> recentMessages = messages.subList(fromIndex, messages.size());

        return recentMessages.stream()
                .map(m -> new ClaudeService.ConversationTurn(
                        m.getUserMessage(),
                        m.getAssistantMessage()
                ))
                .toList();
    }

    public void clearSession(UUID userId, String sessionId) {
        conversationRepository.deleteByUserIdAndSessionId(userId, sessionId);
        logger.debug("Cleared session {} for user {}", sessionId, userId);
    }

    public long getSessionMessageCount(UUID userId, String sessionId) {
        return conversationRepository.countByUserIdAndSessionId(userId, sessionId);
    }
}
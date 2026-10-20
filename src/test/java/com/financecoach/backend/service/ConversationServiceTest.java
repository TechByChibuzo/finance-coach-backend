package com.financecoach.backend.service;

import com.financecoach.backend.model.ConversationMessage;
import com.financecoach.backend.repository.ConversationMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationMessageRepository repository;

    @InjectMocks
    private ConversationService conversationService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void saveMessage_storesTheTurnForTheSession() {
        conversationService.saveMessage(userId, "s1", "How am I doing?", "Pretty well!");

        ArgumentCaptor<ConversationMessage> saved = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getSessionId()).isEqualTo("s1");
        assertThat(saved.getValue().getUserMessage()).isEqualTo("How am I doing?");
        assertThat(saved.getValue().getAssistantMessage()).isEqualTo("Pretty well!");
    }

    @Test
    void getHistory_returnsOnlyTheLastTenTurns_inOrder() {
        List<ConversationMessage> fifteen = IntStream.range(0, 15)
                .mapToObj(i -> new ConversationMessage(userId, "s1", "q" + i, "a" + i))
                .toList();
        when(repository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, "s1")).thenReturn(fifteen);

        List<ClaudeService.ConversationTurn> history = conversationService.getHistory(userId, "s1");

        assertThat(history).hasSize(10);
        assertThat(history.get(0).userMessage()).isEqualTo("q5");
        assertThat(history.get(9).assistantMessage()).isEqualTo("a14");
    }

    @Test
    void getHistory_withFewTurns_returnsThemAll() {
        when(repository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, "s1"))
                .thenReturn(List.of(new ConversationMessage(userId, "s1", "q", "a")));

        assertThat(conversationService.getHistory(userId, "s1")).hasSize(1);
    }

    @Test
    void clearSession_andCount_delegateToTheRepository() {
        when(repository.countByUserIdAndSessionId(userId, "s1")).thenReturn(4L);

        conversationService.clearSession(userId, "s1");

        verify(repository).deleteByUserIdAndSessionId(userId, "s1");
        assertThat(conversationService.getSessionMessageCount(userId, "s1")).isEqualTo(4L);
    }
}

package com.financecoach.backend.service;

import com.financecoach.backend.exception.AiServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceTest {

    @Mock
    private BedrockRuntimeClient bedrockClient;

    private ClaudeService claudeService;

    @BeforeEach
    void setUp() {
        claudeService = new ClaudeService(bedrockClient, "anthropic.claude-test", 1000);
    }

    @Test
    void chat_returnsTheModelsText() {
        stubBedrockBody("{\"content\": [{\"type\": \"text\", \"text\": \"You spent $120 on food.\"}]}");

        assertThat(claudeService.chat("How much on food?")).isEqualTo("You spent $120 on food.");
    }

    @Test
    void chat_whenBedrockFails_throwsInsteadOfReturningAnApology() {
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(ThrottlingException.builder().message("Too many requests").build());

        assertThatThrownBy(() -> claudeService.chat("Hi"))
                .isInstanceOf(AiServiceUnavailableException.class)
                .hasCauseInstanceOf(ThrottlingException.class);
    }

    @Test
    void chat_whenResponseHasNoText_throws() {
        stubBedrockBody("{\"content\": []}");

        assertThatThrownBy(() -> claudeService.chat("Hi"))
                .isInstanceOf(AiServiceUnavailableException.class);
    }

    @Test
    void chat_sendsSystemPromptAndHistoryInOrder() {
        stubBedrockBody("{\"content\": [{\"type\": \"text\", \"text\": \"ok\"}]}");

        claudeService.chat("And now?", "You are a coach",
                List.of(new ClaudeService.ConversationTurn("First question", "First answer")));

        String body = sentBody();
        assertThat(body).contains("\"system\":\"You are a coach\"").contains("\"max_tokens\":1000");
        assertThat(body.indexOf("First question")).isLessThan(body.indexOf("First answer"));
        assertThat(body.indexOf("First answer")).isLessThan(body.indexOf("And now?"));
    }

    @Test
    void chat_withoutASystemPrompt_omitsIt() {
        stubBedrockBody("{\"content\": [{\"type\": \"text\", \"text\": \"ok\"}]}");

        claudeService.chat("Hi", "");

        assertThat(sentBody()).doesNotContain("\"system\"");
    }

    @Test
    void chat_whenTheResponseIsMalformed_throws() {
        stubBedrockBody("not json");

        assertThatThrownBy(() -> claudeService.chat("Hi")).isInstanceOf(AiServiceUnavailableException.class);
    }

    private String sentBody() {
        ArgumentCaptor<InvokeModelRequest> request = ArgumentCaptor.forClass(InvokeModelRequest.class);
        verify(bedrockClient).invokeModel(request.capture());
        assertThat(request.getValue().modelId()).isEqualTo("anthropic.claude-test");
        return request.getValue().body().asUtf8String();
    }

    private void stubBedrockBody(String json) {
        when(bedrockClient.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(json)).build());
    }
}

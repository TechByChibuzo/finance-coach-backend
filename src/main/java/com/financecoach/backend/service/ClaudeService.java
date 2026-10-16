package com.financecoach.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financecoach.backend.exception.AiServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClaudeService {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeService.class);

    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;

    @Autowired
    public ClaudeService(@Value("${claude.model:anthropic.claude-sonnet-4-5-20250929-v1:0}") String model,
                         @Value("${claude.max-tokens:4096}") int maxTokens) {
        this(BedrockRuntimeClient.builder()
                        .region(Region.US_EAST_1)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build(),
                model, maxTokens);
    }

    ClaudeService(BedrockRuntimeClient bedrockClient, String model, int maxTokens) {
        this.bedrockClient = bedrockClient;
        this.model = model;
        this.maxTokens = maxTokens;
        this.objectMapper = new ObjectMapper();
    }

    public String chat(String userMessage, String systemPrompt) {
        return chat(userMessage, systemPrompt, List.of());
    }

    public String chat(String userMessage) {
        return chat(userMessage, null, List.of());
    }

    /**
     * Send a conversation to Claude and return its reply.
     *
     * @throws AiServiceUnavailableException if Bedrock fails or returns no text, so callers
     *         don't store or count a failed reply as a real answer
     */
    public String chat(String userMessage, String systemPrompt,
                       List<ConversationTurn> history) {
        try {
            List<Map<String, Object>> messages = new ArrayList<>();

            // Add conversation history
            for (ConversationTurn turn : history) {
                messages.add(Map.of(
                        "role", "user",
                        "content", turn.userMessage()
                ));
                messages.add(Map.of(
                        "role", "assistant",
                        "content", turn.assistantMessage()
                ));
            }

            // Add current message
            messages.add(Map.of(
                    "role", "user",
                    "content", userMessage
            ));

            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("anthropic_version", "bedrock-2023-05-31");
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("messages", messages);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                requestBody.put("system", systemPrompt);
            }

            String requestJson = objectMapper.writeValueAsString(requestBody);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(model)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(requestJson))
                    .build();

            InvokeModelResponse response = bedrockClient.invokeModel(request);
            String responseBody = response.body().asUtf8String();

            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> content = (List<Map<String, Object>>) responseMap.get("content");

            if (content != null && !content.isEmpty() && content.get(0).get("text") != null) {
                return (String) content.get(0).get("text");
            }

            throw new AiServiceUnavailableException("Bedrock returned no text content");

        } catch (AiServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error calling Bedrock API: {}", e.getMessage());
            throw new AiServiceUnavailableException("Error calling Bedrock API", e);
        }
    }

    public record ConversationTurn(String userMessage, String assistantMessage) {}
}
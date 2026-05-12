package com.financecoach.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ClaudeService(@Value("${claude.model:anthropic.claude-sonnet-4-5-20250929-v1:0}") String model,
                         @Value("${claude.max-tokens:4096}") int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.objectMapper = new ObjectMapper();
        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    public String chat(String userMessage, String systemPrompt) {
        return chat(userMessage, systemPrompt, List.of());
    }

    public String chat(String userMessage) {
        return chat(userMessage, null, List.of());
    }

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

            if (content != null && !content.isEmpty()) {
                return (String) content.get(0).get("text");
            }

            return "I apologize, but I couldn't generate a response. Please try again.";

        } catch (Exception e) {
            logger.error("Error calling Bedrock API: {}", e.getMessage());
            return "I'm having trouble connecting right now. Please try again later.";
        }
    }

    public record ConversationTurn(String userMessage, String assistantMessage) {}
}
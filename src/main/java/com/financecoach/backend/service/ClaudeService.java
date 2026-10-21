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
import java.util.stream.Collectors;

@Service
public class ClaudeService {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeService.class);

    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;
    private final String effort;

    static final String REFUSAL_REPLY =
            "Sorry, I can't help with that request. I can answer questions about your spending, budgets and savings.";

    @Autowired
    public ClaudeService(@Value("${claude.model:us.anthropic.claude-sonnet-5-5}") String model,
                         @Value("${claude.max-tokens:16000}") int maxTokens,
                         @Value("${claude.effort:low}") String effort,
                         @Value("${aws.region}") String region) {
        this(BedrockRuntimeClient.builder()
                        .region(Region.of(region))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build(),
                model, maxTokens, effort);
    }

    ClaudeService(BedrockRuntimeClient bedrockClient, String model, int maxTokens, String effort) {
        this.bedrockClient = bedrockClient;
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = effort;
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
            // Sonnet 5.5 thinks adaptively by default; effort sets how much (low suits chat)
            if (effort != null && !effort.isBlank()) {
                requestBody.put("output_config", Map.of("effort", effort));
            }

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
            Object stopReason = responseMap.get("stop_reason");

            // A safety decline is a normal response with no usable answer
            if ("refusal".equals(stopReason)) {
                logger.warn("Claude declined the request: {}", responseMap.get("stop_details"));
                return REFUSAL_REPLY;
            }

            // The reply can start with thinking blocks, so read the text blocks by type
            List<Map<String, Object>> content = (List<Map<String, Object>>) responseMap.get("content");
            String text = content == null ? "" : content.stream()
                    .filter(block -> "text".equals(block.get("type")) && block.get("text") != null)
                    .map(block -> (String) block.get("text"))
                    .collect(Collectors.joining());

            if (!text.isBlank()) {
                return text;
            }

            throw new AiServiceUnavailableException("Bedrock returned no text content (stop_reason " + stopReason + ")");

        } catch (AiServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error calling Bedrock API: {}", e.getMessage());
            throw new AiServiceUnavailableException("Error calling Bedrock API", e);
        }
    }

    public record ConversationTurn(String userMessage, String assistantMessage) {}
}
// src/main/java/com/financecoach/userservice/service/ClaudeService.java
package com.financecoach.userservice.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ClaudeService {

    private final AnthropicClient client;
    private final String model;
    private final int maxTokens;

    public ClaudeService(@Value("${claude.api-key}") String apiKey,
                         @Value("${claude.model}") String model,
                         @Value("${claude.max-tokens}") int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;

        // Initialize client with API key
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    /**
     * Send a message to Claude and get a response
     */
    public String chat(String userMessage, String systemPrompt) {
        try {
            // Build message parameters
            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .addUserMessage(userMessage);

            // Add system prompt if provided
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                paramsBuilder.system(systemPrompt);
            }

            // Make API call
            Message message = client.messages().create(paramsBuilder.build());

            // Extract text from response
            if (message.content() != null && !message.content().isEmpty()) {
                ContentBlock firstBlock = message.content().get(0);

                // Get TextBlock from Optional
                Optional<TextBlock> textBlockOptional = firstBlock.text();
                if (textBlockOptional.isPresent()) {
                    TextBlock textBlock = textBlockOptional.get();
                    return textBlock.text();
                }
            }

            return "I apologize, but I couldn't generate a response. Please try again.";

        } catch (Exception e) {
            System.err.println("Error calling Claude API: " + e.getMessage());
            e.printStackTrace();
            return "I'm having trouble connecting right now. Please try again later.";
        }
    }

    /**
     * Simplified chat without system prompt
     */
    public String chat(String userMessage) {
        return chat(userMessage, null);
    }
}
// src/main/java/com/financecoach/backend/dto/ChatRequest.java
package com.financecoach.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 2000, message = "Message must be less than 2000 characters")
    private String message;

    public ChatRequest() {}

    public ChatRequest(String message) {
        this.message = message;
    }
}
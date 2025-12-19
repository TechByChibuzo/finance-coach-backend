// src/main/java/com/financecoach/backend/dto/plaid/ExchangeTokenRequest.java
package com.financecoach.backend.dto.plaid;

import jakarta.validation.constraints.NotBlank;

public class ExchangeTokenRequest {

    @NotBlank(message = "Public token is required")
    private String publicToken;

    public ExchangeTokenRequest() {}

    public ExchangeTokenRequest(String publicToken) {
        this.publicToken = publicToken;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public void setPublicToken(String publicToken) {
        this.publicToken = publicToken;
    }
}
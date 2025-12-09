// src/main/java/com/financecoach/userservice/dto/plaid/ExchangeTokenRequest.java
package com.financecoach.userservice.dto.plaid;

public class ExchangeTokenRequest {
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
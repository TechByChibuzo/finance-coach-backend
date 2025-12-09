// src/main/java/com/financecoach/userservice/dto/plaid/LinkTokenResponse.java
package com.financecoach.userservice.dto.plaid;

public class LinkTokenResponse {
    private String linkToken;
    private String expiration;

    public LinkTokenResponse() {}

    public LinkTokenResponse(String linkToken, String expiration) {
        this.linkToken = linkToken;
        this.expiration = expiration;
    }

    public String getLinkToken() {
        return linkToken;
    }

    public void setLinkToken(String linkToken) {
        this.linkToken = linkToken;
    }

    public String getExpiration() {
        return expiration;
    }

    public void setExpiration(String expiration) {
        this.expiration = expiration;
    }
}
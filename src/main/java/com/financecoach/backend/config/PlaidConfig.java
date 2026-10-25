package com.financecoach.backend.config;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

@Configuration
public class PlaidConfig {

    private static final Logger logger = LoggerFactory.getLogger(PlaidConfig.class);

    @Value("${plaid.client-id}")
    private String clientId;

    @Value("${plaid.secret}")
    private String secret;

    @Value("${plaid.environment}")
    private String environment;

    public String plaidEnv;

    @Bean
    public PlaidApi plaidClient() {
        // Determine Plaid base URL based on environment
        switch (environment.toLowerCase()) {
            case "sandbox":
                plaidEnv = ApiClient.Sandbox;
                break;
            case "production":
                plaidEnv = ApiClient.Production;
                break;
            default:
                // A typo here would silently point production at the sandbox
                logger.warn("Unknown plaid.environment '{}', using sandbox", environment);
                plaidEnv = ApiClient.Sandbox;
        }

        // Create API keys map
        HashMap<String, String> apiKeys = new HashMap<>();
        apiKeys.put("clientId", clientId);
        apiKeys.put("secret", secret);

        // Create API client
        ApiClient apiClient = new ApiClient(apiKeys);

        // Set environment
        apiClient.setPlaidAdapter(plaidEnv);

        // Return the PlaidApi service for injection
        return apiClient.createService(PlaidApi.class);
    }
}

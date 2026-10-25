package com.financecoach.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Timeouts for every RestClient Spring Boot builds, which includes Spring AI's OpenAI embedding
 * calls. Without them a stalled response hangs the request that needs it: the AI coach embeds
 * each question before answering it. Spring AI's own retry still applies on top.
 */
@Configuration
public class HttpClientTimeoutsConfig {

    @Bean
    public RestClientCustomizer restClientTimeouts(
            @Value("${app.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:30s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }
}

package com.financecoach.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Builds the real AWS clients the way Spring does at startup (the public constructors), so a
 * dependency update that leaves the AWS SDK modules on mismatched versions fails here instead of
 * in a production deploy. Building a client makes no network call; credentials load lazily.
 */
class AwsClientsTest {

    @Test
    void theS3ClientAndPresigner_build() {
        assertThatCode(() -> new S3Service("us-east-2", "test-bucket")).doesNotThrowAnyException();
    }

    @Test
    void theBedrockClient_builds() {
        assertThatCode(() -> new ClaudeService("test-model", 1000, "", "us-east-2")).doesNotThrowAnyException();
    }

    @Test
    void theBedrockClient_capsEachCall_atTwoMinutes() {
        // Bedrock sends nothing until the whole answer is written, so the SDK's default 30s
        // socket timeout could cut off a long answer; each call gets an explicit overall limit instead
        BedrockRuntimeClient client = (BedrockRuntimeClient) ReflectionTestUtils.getField(
                new ClaudeService("test-model", 1000, "", "us-east-2"), "bedrockClient");

        assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                .contains(Duration.ofMinutes(2));
    }
}

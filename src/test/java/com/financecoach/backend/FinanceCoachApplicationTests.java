package com.financecoach.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application, as production does, against Postgres 16 + pgvector in Docker:
 * every bean wires up, startup runners (constraints, seeding) run, and requests are served.
 * Secrets are dummies; nothing here calls Plaid, Stripe, OpenAI, Bedrock or SendGrid.
 * Skipped when Docker isn't available; CI runners have it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "JWT_SECRET=context-test-jwt-secret-that-is-long-enough-for-hs256-0123456789",
        "JASYPT_ENCRYPTOR_PASSWORD=context-test",
        "OPENAI_API_KEY=sk-context-test",
        "PLAID_CLIENT_ID=context-test",
        "PLAID_SECRET=context-test",
        "SENDGRID_API_KEY=SG.context-test",
        "STRIPE_API_KEY=sk_test_context",
        "STRIPE_WEBHOOK_SECRET=whsec_context",
        "spring.jpa.show-sql=false"
})
@Testcontainers(disabledWithoutDocker = true)
class FinanceCoachApplicationTests {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void theApplicationStarts_andServesRequests() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/subscriptions/plans", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/users/me", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

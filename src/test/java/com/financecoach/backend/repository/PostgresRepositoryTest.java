package com.financecoach.backend.repository;

import com.financecoach.backend.config.DatabaseConstraints;
import com.financecoach.backend.config.JasyptConfig;
import com.financecoach.backend.security.EncryptedStringConverter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that run repositories, custom SQL and the startup constraints against a real
 * Postgres 16 with pgvector (as in production), so queries, encryption and constraints are
 * exercised instead of mocked. Skipped when Docker isn't available; CI runners have it.
 *
 * One container is shared by every subclass. Each test runs in a transaction that is rolled back.
 */
@DataJpaTest(properties = {
        "jasypt.encryptor.password=test-only-key",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JasyptConfig.class, EncryptedStringConverter.class, DatabaseConstraints.class})
@Testcontainers(disabledWithoutDocker = true)
abstract class PostgresRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        // Without Docker, @Testcontainers(disabledWithoutDocker) skips the tests; don't fail loading the class
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseConstraints databaseConstraints;

    // The app adds these at startup (ApplicationRunner), which a @DataJpaTest doesn't run
    @BeforeEach
    void addStartupConstraints() {
        databaseConstraints.run(null);
    }
}

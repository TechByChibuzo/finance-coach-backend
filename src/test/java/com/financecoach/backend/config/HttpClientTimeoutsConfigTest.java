package com.financecoach.backend.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RestClient-based calls (Spring AI's OpenAI embeddings) had no read timeout, so a stalled
 * response could hang the request that needed it.
 */
class HttpClientTimeoutsConfigTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aResponseSlowerThanTheReadTimeout_failsInsteadOfHanging() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        RestClient.Builder builder = RestClient.builder();
        new HttpClientTimeoutsConfig().restClientTimeouts(Duration.ofSeconds(2), Duration.ofMillis(300)).customize(builder);
        RestClient client = builder.baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }
}

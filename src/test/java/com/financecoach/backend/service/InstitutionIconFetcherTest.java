package com.financecoach.backend.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstitutionIconFetcherTest {

    private HttpServer server;
    private InstitutionIconFetcher fetcher;
    private final AtomicReference<String> askedFor = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String contentType = "image/png";
    private volatile byte[] body = {(byte) 0x89, 'P', 'N', 'G'};

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/icon", exchange -> {
            askedFor.set(exchange.getRequestURI().getQuery());
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        fetcher = new InstitutionIconFetcher();
        fetcher.iconService = "http://127.0.0.1:" + server.getAddress().getPort() + "/icon?domain=";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fetchIcon_asksByDomainOnly_andReturnsADataUrl() throws IOException {
        String icon = fetcher.fetchIcon("https://www.chase.com/personal?x=1");

        assertThat(askedFor.get()).isEqualTo("domain=chase.com");
        assertThat(icon).isEqualTo("data:image/png;base64,iVBORw==");
    }

    @Test
    void fetchIcon_keepsTheImageType() throws IOException {
        contentType = "image/jpeg; charset=binary";

        assertThat(fetcher.fetchIcon("chase.com")).startsWith("data:image/jpeg;base64,");
    }

    @Test
    void fetchIcon_siteWithoutAnIcon_isNull() throws IOException {
        status = 404;

        assertThat(fetcher.fetchIcon("https://nobank.example")).isNull();
    }

    @Test
    void fetchIcon_somethingThatIsNotAnImage_isNull() throws IOException {
        contentType = "text/html";

        assertThat(fetcher.fetchIcon("https://www.chase.com")).isNull();
    }

    @Test
    void fetchIcon_tooLarge_isNull() throws IOException {
        body = new byte[64 * 1024 + 1];

        assertThat(fetcher.fetchIcon("https://www.chase.com")).isNull();
    }

    @Test
    void fetchIcon_serverError_throwsSoItCanBeRetried() {
        status = 503;

        assertThatThrownBy(() -> fetcher.fetchIcon("https://www.chase.com")).isInstanceOf(IOException.class);
    }

    @Test
    void fetchIcon_noUsableWebsite_isNullWithoutAsking() throws IOException {
        assertThat(fetcher.fetchIcon(null)).isNull();
        assertThat(fetcher.fetchIcon("  ")).isNull();
        assertThat(fetcher.fetchIcon("https://localhost")).isNull();
        assertThat(fetcher.fetchIcon("http://bad host")).isNull();
        assertThat(askedFor.get()).isNull();
    }
}

package com.financecoach.backend.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Plaid has no logo for many banks (Chase among them) but does give their website. This looks up
 * the site's icon by domain, server side, so the browser never tells a third party which bank the
 * user has, and the icon is stored with the item like a Plaid logo.
 */
@Component
public class InstitutionIconFetcher {

    private static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/x-icon", "image/vnd.microsoft.icon");

    // Only the domain is sent, never the full URL, so the lookup can't be pointed elsewhere
    String iconService = "https://www.google.com/s2/favicons?sz=128&domain=";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * The site's icon as a data URL, or null when it has none. Throws when the lookup itself fails
     * (network, server error), so the caller can try again later.
     */
    public String fetchIcon(String website) throws IOException {
        String domain = domain(website);
        if (domain == null) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(iconService + URLEncoder.encode(domain, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Icon lookup interrupted", e);
        }

        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("Icon lookup returned HTTP " + response.statusCode());
        }
        String type = response.headers().firstValue("Content-Type").orElse("")
                .split(";")[0].trim().toLowerCase(Locale.ROOT);
        byte[] body = response.body();
        if (!IMAGE_TYPES.contains(type) || body.length == 0 || body.length > MAX_BYTES) {
            return null;
        }
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(body);
    }

    // "https://www.chase.com/personal" -> "chase.com"
    static String domain(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String url = website.trim();
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null || !host.contains(".")) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

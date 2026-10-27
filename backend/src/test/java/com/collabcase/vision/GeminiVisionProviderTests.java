package com.collabcase.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GeminiVisionProviderTests {
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiVisionProvider provider() {
        return new GeminiVisionProvider("http://127.0.0.1:" + server.getAddress().getPort(), "clave", "gemini-x", 5);
    }

    private VisionProviderRequest request() {
        return new VisionProviderRequest(new byte[] {1, 2, 3}, "image/png", "lee el diagrama",
                Map.of("type", "object"));
    }

    @Test
    void sendsImageAndSchemaAndReturnsTheJsonText() {
        reply = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"classes\\":"},{"text":"[]}"}]}}]}""";

        VisionProviderResponse result = provider().extract(request());

        assertThat(result.content()).isEqualTo("{\"classes\":[]}");
        assertThat(result.provider()).isEqualTo("gemini");
        assertThat(path.get()).isEqualTo("/models/gemini-x:generateContent");
        assertThat(key.get()).isEqualTo("clave");
        JsonNode sent = new ObjectMapper().readTree(body.get());
        JsonNode parts = sent.path("contents").path(0).path("parts");
        assertThat(parts.path(0).path("text").asText()).isEqualTo("lee el diagrama");
        assertThat(parts.path(1).path("inline_data").path("mime_type").asText()).isEqualTo("image/png");
        assertThat(parts.path(1).path("inline_data").path("data").asText()).isEqualTo("AQID");
        assertThat(sent.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(sent.path("generationConfig").path("responseJsonSchema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void rateLimitBecomesTooManyRequests() {
        status = 429;
        reply = "{\"error\":{\"code\":429}}";

        assertThatThrownBy(() -> provider().extract(request()))
                .isInstanceOfSatisfying(VisionException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void emptyAnswerIsUnavailable() {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[]}}]}";

        assertThatThrownBy(() -> provider().extract(request()))
                .isInstanceOfSatisfying(VisionException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void requiresApiKey() {
        assertThatThrownBy(() -> new GeminiVisionProvider("http://localhost", " ", "m", 5))
                .isInstanceOf(IllegalStateException.class);
    }
}

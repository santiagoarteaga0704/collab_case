package com.collabcase.teamai.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GeminiFallbackLinkTests {
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;

    private static final List<Map<String, String>> MSGS = List.of(
            Map.of("role", "system", "content", "eres la IA del equipo"),
            Map.of("role", "user", "content", "[Ana] hola"),
            Map.of("role", "assistant", "content", "hola Ana"),
            Map.of("role", "user", "content", "agrega Cliente"));

    static class Recorder implements AiJobListener {
        final List<String> events = new ArrayList<>();

        @Override public void queued(int position) { }
        @Override public void started() { events.add("started"); }
        @Override public void chunk(String text) { }
        @Override public void finished(String content) { events.add("finished(" + content + ")"); }
        @Override public void failed(String reason) { events.add("failed(" + reason + ")"); }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
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

    private AiJobQueue queueWithFallback() {
        AiJobQueue queue = new AiJobQueue();
        new GeminiFallbackLink(queue, "http://127.0.0.1:" + server.getAddress().getPort(), "clave", "gemini-x", 5,
                false);
        return queue;
    }

    @Test
    void answersTheJobWithGeminiWhenThereIsNoConnector() {
        reply = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"mensaje\\":"},{"text":"\\"listo\\"}"}]}}]}""";
        AiJobQueue queue = queueWithFallback();
        Recorder r = new Recorder();

        queue.submit(MSGS, Map.of("type", "object"), r);

        assertThat(r.events).containsExactly("started", "finished({\"mensaje\":\"listo\"})");
        assertThat(path.get()).isEqualTo("/models/gemini-x:generateContent");
        JsonNode sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.path("systemInstruction").path("parts").path(0).path("text").asText())
                .isEqualTo("eres la IA del equipo");
        assertThat(sent.path("contents")).hasSize(3);
        assertThat(sent.path("contents").path(1).path("role").asText()).isEqualTo("model");
        assertThat(sent.path("contents").path(2).path("parts").path(0).path("text").asText()).isEqualTo("agrega Cliente");
        assertThat(sent.path("generationConfig").path("responseJsonSchema").path("type").asText()).isEqualTo("object");
        assertThat(queue.size()).isZero();
    }

    @Test
    void aGeminiErrorEndsTheJobAsFailed() {
        status = 500;
        reply = "{}";
        AiJobQueue queue = queueWithFallback();
        Recorder r = new Recorder();

        queue.submit(MSGS, null, r);

        assertThat(r.events).containsExactly("started", "failed(La IA no está disponible en este momento)");
        assertThat(queue.size()).isZero();
    }
}

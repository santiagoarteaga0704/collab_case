package com.collabcase.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GeminiSpeechToTextProviderTests {
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;

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

    private GeminiSpeechToTextProvider provider() {
        return new GeminiSpeechToTextProvider("http://127.0.0.1:" + server.getAddress().getPort(), "clave", "gemini-x", 5);
    }

    private static String answer(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
    }

    @Test
    void sendsAudioInlineWithoutCodecParameterAndReturnsTrimmedText() {
        reply = answer("  agrega la clase Cliente\\n");

        SpeechTranscription result = provider().transcribe(new byte[] {1, 2, 3}, "voz.webm", "audio/webm;codecs=opus");

        assertThat(result.text()).isEqualTo("agrega la clase Cliente");
        assertThat(result.provider()).isEqualTo("gemini");
        assertThat(path.get()).isEqualTo("/models/gemini-x:generateContent");
        JsonNode inline = new ObjectMapper().readTree(body.get())
                .path("contents").path(0).path("parts").path(1).path("inline_data");
        assertThat(inline.path("mime_type").asText()).isEqualTo("audio/webm");
        assertThat(inline.path("data").asText()).isEqualTo("AQID");
    }

    @Test
    void noSpeechMarkerIsUnprocessable() {
        reply = answer("SIN_VOZ");

        assertThatThrownBy(() -> provider().transcribe(new byte[] {1}, "voz.webm", "audio/webm"))
                .isInstanceOfSatisfying(SpeechToTextException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void rejectedAudioIsUnprocessable() {
        status = 400;
        reply = "{\"error\":{\"code\":400}}";

        assertThatThrownBy(() -> provider().transcribe(new byte[] {1}, "voz.webm", "audio/webm"))
                .isInstanceOfSatisfying(SpeechToTextException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void baseMimeTypeDropsParameters() {
        assertThat(GeminiSpeechToTextProvider.baseMimeType("Audio/OGG; codecs=opus")).isEqualTo("audio/ogg");
        assertThat(GeminiSpeechToTextProvider.baseMimeType(null)).isEqualTo("audio/webm");
    }
}

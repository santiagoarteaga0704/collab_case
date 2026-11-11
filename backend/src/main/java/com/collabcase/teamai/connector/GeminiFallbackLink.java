package com.collabcase.teamai.connector;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * IA de respaldo en la nube: cuando el conector de la laptop no está, la cola le pasa los
 * trabajos a Gemini (generateContent) y la respuesta vuelve a la cola como si fuera del conector.
 */
@Component
@ConditionalOnProperty(name = "teamai.fallback.provider", havingValue = "gemini")
public class GeminiFallbackLink implements ConnectorLink {
    private static final Logger log = LoggerFactory.getLogger(GeminiFallbackLink.class);
    static final String BUSY = "La IA está saturada, prueba de nuevo en un minuto";
    static final String DAILY_LIMIT = "La IA llegó a su límite de uso de hoy, prueba más tarde";
    static final String UNAVAILABLE = "La IA no está disponible en este momento";
    /** Intentos ante un error pasajero de Gemini (503 "overloaded", 500, red). */
    static final int ATTEMPTS = 3;

    private final AiJobQueue queue;
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final boolean async;
    private final long retryDelayMillis;

    @Autowired
    public GeminiFallbackLink(AiJobQueue queue,
            @Value("${teamai.fallback.gemini.base-url}") String baseUrl,
            @Value("${teamai.fallback.gemini.api-key}") String apiKey,
            @Value("${teamai.fallback.gemini.model}") String model,
            @Value("${teamai.fallback.timeout-seconds}") long timeoutSeconds) {
        this(queue, baseUrl, apiKey, model, timeoutSeconds, true);
    }

    GeminiFallbackLink(AiJobQueue queue, String baseUrl, String apiKey, String model, long timeoutSeconds,
            boolean async) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required when IA_RESPALDO=gemini");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.queue = queue;
        this.apiKey = apiKey;
        this.model = model;
        this.async = async;
        this.retryDelayMillis = async ? 1500 : 0;
        queue.useFallback(this, model);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void send(Map<String, Object> payload) {
        // "cancel": la cola ya soltó el trabajo y descarta su respuesta si llega
        if (!"job".equals(payload.get("kind"))) return;
        UUID id = UUID.fromString((String) payload.get("id"));
        List<Map<String, String>> messages = (List<Map<String, String>>) payload.get("messages");
        Map<String, Object> format = (Map<String, Object>) payload.get("format");
        Runnable work = () -> run(id, messages, format);
        if (async) Thread.ofVirtual().name("ia-respaldo-" + id).start(work);
        else work.run();
    }

    private void run(UUID id, List<Map<String, String>> messages, Map<String, Object> format) {
        Map<String, Object> body = body(messages, format);
        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode response = client.post().uri("/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey).body(body).retrieve().body(JsonNode.class);
                String text = textOf(response);
                if (text == null || text.isBlank()) throw new IllegalStateException("Respuesta vacía de Gemini");
                queue.onFinished(id, text);
                return;
            } catch (HttpClientErrorException.TooManyRequests e) {
                boolean daily = e.getResponseBodyAsString().contains("PerDay");
                log.warn("Gemini rechazó el trabajo {} por cuota ({})", id, daily ? "diaria" : "por minuto");
                queue.onFailed(id, daily ? DAILY_LIMIT : BUSY);
                return;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                if (attempt < ATTEMPTS) {
                    log.info("Gemini falló de forma pasajera en el trabajo {} (intento {}): {}", id, attempt, e.getMessage());
                    pause(attempt);
                    continue;
                }
                log.warn("La IA de respaldo (Gemini) falló en el trabajo {} tras {} intentos", id, attempt, e);
                queue.onFailed(id, e instanceof HttpServerErrorException.ServiceUnavailable ? BUSY : UNAVAILABLE);
                return;
            } catch (Exception e) {
                log.warn("La IA de respaldo (Gemini) falló en el trabajo {}", id, e);
                queue.onFailed(id, UNAVAILABLE);
                return;
            }
        }
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(retryDelayMillis * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Pasa los mensajes estilo Ollama (system/user/assistant) al formato de Gemini. */
    static Map<String, Object> body(List<Map<String, String>> messages, Map<String, Object> format) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Map<String, String> m : messages) {
            String role = m.get("role");
            String content = m.getOrDefault("content", "");
            if ("system".equals(role)) {
                if (!system.isEmpty()) system.append("\n\n");
                system.append(content);
            } else {
                contents.add(Map.of("role", "assistant".equals(role) ? "model" : "user",
                        "parts", List.of(Map.of("text", content))));
            }
        }
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.3);
        generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
        if (format != null) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", format);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (!system.isEmpty()) body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system.toString()))));
        body.put("contents", contents);
        body.put("generationConfig", generationConfig);
        return body;
    }

    /** Une las partes de texto del primer candidato de una respuesta de Gemini. */
    private static String textOf(JsonNode response) {
        if (response == null) return null;
        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
        }
        return text.toString();
    }
}

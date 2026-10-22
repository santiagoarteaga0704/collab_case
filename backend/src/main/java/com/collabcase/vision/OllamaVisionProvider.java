package com.collabcase.vision;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "vision.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaVisionProvider implements VisionProvider {
    private final RestClient client;
    private final String model;

    public OllamaVisionProvider(@Value("${vision.ollama.base-url}") String baseUrl,
            @Value("${vision.ollama.model}") String model,
            @Value("${vision.timeout-seconds}") long timeoutSeconds) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
    }

    @Override
    public VisionProviderResponse extract(VisionProviderRequest request) {
        try {
            Map<String, Object> message = Map.of(
                    "role", "user", "content", request.prompt(),
                    "images", List.of(Base64.getEncoder().encodeToString(request.image())));
            Map<String, Object> body = Map.of(
                    "model", model, "stream", false, "format", request.jsonSchema(),
                    "options", Map.of("temperature", 0), "messages", List.of(message));
            JsonNode response = client.post().uri("/api/chat").body(body).retrieve().body(JsonNode.class);
            String content = response == null ? null : response.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) throw new IllegalStateException("Empty Ollama response");
            return new VisionProviderResponse(content, "ollama");
        } catch (Exception exception) {
            throw new VisionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo extraer el diagrama con Ollama. Comprueba que Ollama y el modelo visual esten disponibles.",
                    exception);
        }
    }
}

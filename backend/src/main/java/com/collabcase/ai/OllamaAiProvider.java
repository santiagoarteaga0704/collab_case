package com.collabcase.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaAiProvider implements AiProvider {
    private final RestClient client;
    private final String model;

    public OllamaAiProvider(
            @Value("${ai.ollama.base-url}") String baseUrl,
            @Value("${ai.ollama.model}") String model,
            @Value("${ai.timeout-seconds}") long timeoutSeconds) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.model = model;
    }

    @Override
    public AiProviderResponse generate(AiProviderRequest request) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "stream", false,
                    "format", request.jsonSchema(),
                    "options", Map.of("temperature", 0),
                    "messages", List.of(
                            Map.of("role", "system", "content", request.systemPrompt()),
                            Map.of("role", "user", "content", request.userPrompt())));
            JsonNode response = client.post().uri("/api/chat").body(body).retrieve().body(JsonNode.class);
            String content = response == null ? null : response.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) throw new IllegalStateException("Empty Ollama response");
            return new AiProviderResponse(content, "ollama");
        } catch (Exception exception) {
            throw new AiProviderException(
                    "No se pudo obtener respuesta de Ollama. Comprueba que el servicio y el modelo estén disponibles.",
                    exception);
        }
    }
}

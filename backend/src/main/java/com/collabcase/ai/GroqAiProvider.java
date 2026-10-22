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
@ConditionalOnProperty(name = "ai.provider", havingValue = "groq")
public class GroqAiProvider implements AiProvider {
    private final RestClient client;
    private final String apiKey;
    private final String model;

    public GroqAiProvider(
            @Value("${ai.groq.base-url}") String baseUrl,
            @Value("${ai.groq.api-key}") String apiKey,
            @Value("${ai.groq.model}") String model,
            @Value("${ai.timeout-seconds}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is required when AI_PROVIDER=groq");
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public AiProviderResponse generate(AiProviderRequest request) {
        try {
            Map<String, Object> responseFormat = Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "uml_analysis",
                            "strict", false,
                            "schema", request.jsonSchema()));
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content", request.systemPrompt()),
                            Map.of("role", "user", "content", request.userPrompt())),
                    "response_format", responseFormat);
            JsonNode response = client.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body).retrieve().body(JsonNode.class);
            String content = response == null ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) throw new IllegalStateException("Empty Groq response");
            return new AiProviderResponse(content, "groq");
        } catch (Exception exception) {
            throw new AiProviderException(
                    "No se pudo obtener respuesta de Groq. Comprueba la clave, el modelo y los limites de uso.",
                    exception);
        }
    }
}

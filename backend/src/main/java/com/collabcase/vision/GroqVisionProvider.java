package com.collabcase.vision;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "vision.provider", havingValue = "groq")
public class GroqVisionProvider implements VisionProvider {
    private final RestClient client;
    private final String apiKey;
    private final String model;

    public GroqVisionProvider(@Value("${vision.groq.base-url}") String baseUrl,
            @Value("${vision.groq.api-key}") String apiKey,
            @Value("${vision.groq.model}") String model,
            @Value("${vision.timeout-seconds}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is required when VISION_PROVIDER=groq");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public VisionProviderResponse extract(VisionProviderRequest request) {
        try {
            String dataUrl = "data:" + request.contentType() + ";base64,"
                    + Base64.getEncoder().encodeToString(request.image());
            List<Map<String, Object>> content = List.of(
                    Map.of("type", "text", "text", request.prompt()),
                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
            Map<String, Object> responseFormat = Map.of("type", "json_schema", "json_schema", Map.of(
                    "name", "uml_image_extraction", "strict", true, "schema", request.jsonSchema()));
            Map<String, Object> body = Map.of(
                    "model", model, "messages", List.of(Map.of("role", "user", "content", content)),
                    "temperature", 0, "reasoning_effort", "none", "response_format", responseFormat);
            JsonNode response = client.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey).body(body).retrieve().body(JsonNode.class);
            String result = response == null ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            if (result == null || result.isBlank()) throw new IllegalStateException("Empty Groq response");
            return new VisionProviderResponse(result, "groq");
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new VisionException(HttpStatus.SERVICE_UNAVAILABLE, "La configuracion de Groq no es valida.", exception);
        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new VisionException(HttpStatus.TOO_MANY_REQUESTS,
                    "Se alcanzo temporalmente el limite de vision de Groq.", exception);
        } catch (VisionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VisionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de vision de Groq no esta disponible.", exception);
        }
    }
}

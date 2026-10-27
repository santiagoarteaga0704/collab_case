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

/** Lee la foto de la pizarra con Gemini (API generateContent) y devuelve el JSON del esquema UML. */
@Component
@ConditionalOnProperty(name = "vision.provider", havingValue = "gemini")
public class GeminiVisionProvider implements VisionProvider {
    private final RestClient client;
    private final String apiKey;
    private final String model;

    public GeminiVisionProvider(@Value("${vision.gemini.base-url}") String baseUrl,
            @Value("${vision.gemini.api-key}") String apiKey,
            @Value("${vision.gemini.model}") String model,
            @Value("${vision.timeout-seconds}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required when VISION_PROVIDER=gemini");
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
            List<Map<String, Object>> parts = List.of(
                    Map.of("text", request.prompt()),
                    Map.of("inline_data", Map.of("mime_type", request.contentType(),
                            "data", Base64.getEncoder().encodeToString(request.image()))));
            Map<String, Object> generationConfig = Map.of(
                    "temperature", 0,
                    "responseMimeType", "application/json",
                    "responseJsonSchema", request.jsonSchema(),
                    "thinkingConfig", Map.of("thinkingBudget", 0));
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", parts)),
                    "generationConfig", generationConfig);
            JsonNode response = client.post().uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey).body(body).retrieve().body(JsonNode.class);
            String result = textOf(response);
            if (result == null || result.isBlank()) throw new IllegalStateException("Empty Gemini response");
            return new VisionProviderResponse(result, "gemini");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden exception) {
            throw new VisionException(HttpStatus.SERVICE_UNAVAILABLE, "La configuracion de Gemini no es valida.", exception);
        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new VisionException(HttpStatus.TOO_MANY_REQUESTS,
                    "Se alcanzo temporalmente el limite de vision de Gemini.", exception);
        } catch (VisionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VisionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de vision de Gemini no esta disponible.", exception);
        }
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

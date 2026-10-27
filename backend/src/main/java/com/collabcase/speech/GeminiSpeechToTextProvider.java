package com.collabcase.speech;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/** Transcribe la grabación con Gemini: el audio va en línea y se pide solo el texto dicho. */
@Component
@ConditionalOnProperty(name = "stt.provider", havingValue = "gemini")
public class GeminiSpeechToTextProvider implements SpeechToTextProvider {
    static final String PROMPT = "Transcribe literalmente lo que se dice en este audio, en el idioma en que se habla "
            + "(normalmente espanol). Responde solo con la transcripcion, sin comillas ni comentarios. "
            + "Si no se escucha ninguna voz, responde exactamente: SIN_VOZ";
    private static final String NO_SPEECH = "SIN_VOZ";

    private final RestClient client;
    private final String apiKey;
    private final String model;

    public GeminiSpeechToTextProvider(
            @Value("${stt.gemini.base-url}") String baseUrl,
            @Value("${stt.gemini.api-key}") String apiKey,
            @Value("${stt.gemini.model}") String model,
            @Value("${stt.timeout-seconds}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required when STT_PROVIDER=gemini");
        }
        this.client = SpeechHttpClientFactory.create(baseUrl, timeoutSeconds);
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public SpeechTranscription transcribe(byte[] audio, String filename, String contentType) {
        try {
            List<Map<String, Object>> parts = List.of(
                    Map.of("text", PROMPT),
                    Map.of("inline_data", Map.of("mime_type", baseMimeType(contentType),
                            "data", Base64.getEncoder().encodeToString(audio))));
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", parts)),
                    "generationConfig", Map.of("temperature", 0, "thinkingConfig", Map.of("thinkingBudget", 0)));
            JsonNode response = client.post().uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey).body(body).retrieve().body(JsonNode.class);
            String text = textOf(response);
            if (text == null || text.isBlank() || text.strip().equals(NO_SPEECH)) {
                throw new SpeechToTextException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se detecto voz en la grabacion.");
            }
            return new SpeechTranscription(text.strip(), "gemini");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden exception) {
            throw new SpeechToTextException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La configuracion de transcripcion de Gemini no es valida.", exception);
        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new SpeechToTextException(HttpStatus.TOO_MANY_REQUESTS,
                    "Se alcanzo temporalmente el limite gratuito de transcripcion.", exception);
        } catch (HttpClientErrorException.BadRequest exception) {
            throw new SpeechToTextException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Gemini rechazo el formato o contenido del audio.", exception);
        } catch (SpeechToTextException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SpeechToTextException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de transcripcion de Gemini no esta disponible.", exception);
        }
    }

    /** "audio/webm;codecs=opus" → "audio/webm": Gemini espera el tipo sin parámetros. */
    static String baseMimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "audio/webm";
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.strip().toLowerCase(Locale.ROOT);
    }

    private static String textOf(JsonNode response) {
        if (response == null) return null;
        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
        }
        return text.toString();
    }
}

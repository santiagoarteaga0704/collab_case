package com.collabcase.speech;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "stt.provider", havingValue = "groq")
public class GroqSpeechToTextProvider implements SpeechToTextProvider {
    private final RestClient client;
    private final String apiKey;
    private final String model;

    public GroqSpeechToTextProvider(
            @Value("${stt.groq.base-url}") String baseUrl,
            @Value("${stt.groq.api-key}") String apiKey,
            @Value("${stt.groq.model}") String model,
            @Value("${stt.timeout-seconds}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is required when STT_PROVIDER=groq");
        }
        this.client = SpeechHttpClientFactory.create(baseUrl, timeoutSeconds);
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public SpeechTranscription transcribe(byte[] audio, String filename, String contentType) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            body.add("file", new HttpEntity<>(new MultipartAudio(audio, filename), headers));
            body.add("model", model);
            body.add("language", "es");
            body.add("response_format", "json");
            JsonNode response = client.post().uri("/audio/transcriptions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(body)
                    .retrieve().body(JsonNode.class);
            String text = response == null ? null : response.path("text").asText(null);
            if (text == null || text.isBlank()) {
                throw new SpeechToTextException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se detecto voz en la grabacion.");
            }
            return new SpeechTranscription(text, "groq");
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new SpeechToTextException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La configuracion de transcripcion de Groq no es valida.", exception);
        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new SpeechToTextException(HttpStatus.TOO_MANY_REQUESTS,
                    "Se alcanzo temporalmente el limite gratuito de transcripcion.", exception);
        } catch (HttpClientErrorException.BadRequest exception) {
            throw new SpeechToTextException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Groq rechazo el formato o contenido del audio.", exception);
        } catch (SpeechToTextException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SpeechToTextException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio de transcripcion de Groq no esta disponible.", exception);
        }
    }
}

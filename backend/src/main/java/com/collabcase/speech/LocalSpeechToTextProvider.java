package com.collabcase.speech;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "stt.provider", havingValue = "local", matchIfMissing = true)
public class LocalSpeechToTextProvider implements SpeechToTextProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSpeechToTextProvider.class);
    private final RestClient client;

    public LocalSpeechToTextProvider(
            @Value("${stt.local.base-url}") String baseUrl,
            @Value("${stt.timeout-seconds}") long timeoutSeconds) {
        this.client = SpeechHttpClientFactory.createHttp1(baseUrl, timeoutSeconds);
    }

    @Override
    public SpeechTranscription transcribe(byte[] audio, String filename, String contentType) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new MultipartAudio(audio, filename))
                    .contentType(MediaType.parseMediaType(contentType));
            JsonNode response = client.post().uri("/transcribe")
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(builder.build())
                    .retrieve().body(JsonNode.class);
            String text = response == null ? null : response.path("text").asText(null);
            if (text == null || text.isBlank()) {
                throw new SpeechToTextException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se detecto voz en la grabacion.");
            }
            return new SpeechTranscription(text, "local");
        } catch (SpeechToTextException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            LOGGER.warn("local-stt respondio HTTP {}: {}", exception.getStatusCode(),
                    exception.getResponseBodyAsString());
            if (exception.getStatusCode().value() == 422) {
                throw new SpeechToTextException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El servicio local no pudo procesar el audio recibido.", exception);
            }
            throw new SpeechToTextException(HttpStatus.BAD_GATEWAY,
                    "El servicio local fallo al transcribir el audio.", exception);
        } catch (Exception exception) {
            LOGGER.error("No se pudo comunicar con local-stt", exception);
            throw new SpeechToTextException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio local de transcripcion no esta disponible.", exception);
        }
    }
}

package com.collabcase.speech;

import java.io.IOException;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SpeechToTextService {
    private final SpeechToTextProvider provider;
    private final long maxFileSize;

    public SpeechToTextService(SpeechToTextProvider provider,
            @Value("${stt.max-file-size}") long maxFileSize) {
        this.provider = provider;
        this.maxFileSize = maxFileSize;
    }

    public SpeechTranscriptionResponse transcribe(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SpeechToTextException(HttpStatus.BAD_REQUEST, "El archivo de audio esta vacio.");
        }
        if (file.getSize() > maxFileSize) {
            throw new SpeechToTextException(HttpStatus.PAYLOAD_TOO_LARGE, "El audio supera el limite de 10 MB.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            throw new SpeechToTextException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "El archivo enviado debe ser audio.");
        }
        try {
            String transportContentType = contentType == null ? "application/octet-stream" : contentType;
            String filename = validFilename(file.getOriginalFilename(), transportContentType);
            SpeechTranscription result = provider.transcribe(file.getBytes(), filename, transportContentType);
            return new SpeechTranscriptionResponse(result.text(), result.provider());
        } catch (IOException exception) {
            throw new SpeechToTextException(HttpStatus.BAD_REQUEST, "No se pudo leer el audio.", exception);
        }
    }

    private String validFilename(String originalFilename, String contentType) {
        if (originalFilename != null && !originalFilename.isBlank()) return originalFilename;
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("ogg")) return "recording.ogg";
        if (normalized.contains("mp4") || normalized.contains("m4a")) return "recording.m4a";
        if (normalized.contains("wav")) return "recording.wav";
        return "recording.webm";
    }
}

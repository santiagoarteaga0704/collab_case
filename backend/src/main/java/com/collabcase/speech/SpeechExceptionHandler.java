package com.collabcase.speech;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice(assignableTypes = SpeechController.class)
public class SpeechExceptionHandler {
    @ExceptionHandler(SpeechToTextException.class)
    public ResponseEntity<Map<String, String>> handleSpeech(SpeechToTextException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(Map.of("message", "El audio supera el limite de 10 MB."));
    }
}

package com.collabcase.ai;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AiController.class)
public class AiExceptionHandler {

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<Map<String, String>> handleProvider(AiProviderException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(AiResponseException.class)
    public ResponseEntity<Map<String, String>> handleInvalidResponse(AiResponseException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", exception.getMessage()));
    }
}

package com.collabcase.sequence;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.collabcase.ai.AiProviderException;
import com.collabcase.ai.AiResponseException;

@RestControllerAdvice(assignableTypes = SequenceDiagramController.class)
public class SequenceExceptionHandler {
    @ExceptionHandler(AiProviderException.class)
    ResponseEntity<Map<String, String>> providerUnavailable(AiProviderException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(AiResponseException.class)
    ResponseEntity<Map<String, String>> invalidAiResponse(AiResponseException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("message", exception.getMessage()));
    }
}

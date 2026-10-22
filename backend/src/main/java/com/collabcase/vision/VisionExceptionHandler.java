package com.collabcase.vision;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = VisionController.class)
public class VisionExceptionHandler {
    @ExceptionHandler(VisionException.class)
    public ResponseEntity<Map<String, String>> handle(VisionException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("message", exception.getMessage()));
    }
}

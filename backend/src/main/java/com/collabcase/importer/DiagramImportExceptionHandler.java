package com.collabcase.importer;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DiagramImportController.class)
public class DiagramImportExceptionHandler {
    @ExceptionHandler(XmiImportException.class)
    public ResponseEntity<Map<String, String>> handle(XmiImportException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("message", exception.getMessage()));
    }
}

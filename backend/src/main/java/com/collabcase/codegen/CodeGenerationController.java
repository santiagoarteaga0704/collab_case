package com.collabcase.codegen;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.collabcase.user.User;

@RestController
@RequestMapping("/api/diagrams/{diagramId}/codegen/backend")
public class CodeGenerationController {
    private final BackendGenerationService service;
    public CodeGenerationController(BackendGenerationService service){this.service=service;}
    @GetMapping("/validate") public GenerationValidation validate(@PathVariable UUID diagramId,@AuthenticationPrincipal User user){return service.validate(diagramId,user.getId());}
    @PostMapping public ResponseEntity<byte[]> generate(@PathVariable UUID diagramId,@AuthenticationPrincipal User user){var result=service.generate(diagramId,user.getId());return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.filename(),StandardCharsets.UTF_8).build().toString()).body(result.content());}
}

package com.collabcase.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
    private final ZipService zip;
    public CodeGenerationController(BackendGenerationService service,ZipService zip){this.service=service;this.zip=zip;}
    @GetMapping("/validate") public GenerationValidation validate(@PathVariable UUID diagramId,@AuthenticationPrincipal User user){return service.validate(diagramId,user.getId());}
    @PostMapping public void generate(@PathVariable UUID diagramId,@AuthenticationPrincipal User user,HttpServletResponse response) throws IOException {var prepared=service.prepare(diagramId,user.getId());response.setContentType("application/zip");response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(prepared.filename(),StandardCharsets.UTF_8).build().toString());prepared.writeTo(zip,response.getOutputStream());response.flushBuffer();}
}

package com.collabcase.export;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.collabcase.export.DiagramExportService.Download;
import com.collabcase.user.User;

@RestController
@RequestMapping("/api/diagrams/{diagramId}/export")
public class DiagramExportController {
    private static final MediaType TEXT = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);
    private final DiagramExportService service;

    public DiagramExportController(DiagramExportService service) {
        this.service = service;
    }

    @GetMapping("/json")
    public ResponseEntity<byte[]> json(@PathVariable UUID diagramId, @AuthenticationPrincipal User user) {
        return download(service.json(diagramId, user.getId()), MediaType.APPLICATION_JSON);
    }

    @GetMapping("/plantuml")
    public ResponseEntity<byte[]> plantUml(@PathVariable UUID diagramId, @AuthenticationPrincipal User user) {
        return download(service.plantUml(diagramId, user.getId()), TEXT);
    }

    @GetMapping("/plantuml/preview")
    public ExportPreviewResponse plantUmlPreview(@PathVariable UUID diagramId, @AuthenticationPrincipal User user) {
        return service.plantUmlPreview(diagramId, user.getId());
    }

    @GetMapping("/sql")
    public ResponseEntity<byte[]> sql(@PathVariable UUID diagramId, @AuthenticationPrincipal User user) {
        return download(service.sql(diagramId, user.getId()), TEXT);
    }

    @GetMapping("/sql/preview")
    public ExportPreviewResponse sqlPreview(@PathVariable UUID diagramId, @AuthenticationPrincipal User user) {
        return service.sqlPreview(diagramId, user.getId());
    }

    @GetMapping("/xmi")
    public ResponseEntity<byte[]> xmi(@PathVariable UUID diagramId, @AuthenticationPrincipal User user) {
        return download(service.xmi(diagramId, user.getId()), MediaType.APPLICATION_XML);
    }

    private ResponseEntity<byte[]> download(Download download, MediaType type) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(download.content());
    }
}

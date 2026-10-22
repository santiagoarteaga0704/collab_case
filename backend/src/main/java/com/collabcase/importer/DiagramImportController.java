package com.collabcase.importer;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.collabcase.importer.DiagramImportService.Mode;
import com.collabcase.user.User;

@RestController
@RequestMapping("/api/projects/{projectId}/import")
public class DiagramImportController {
    private final DiagramImportService service;
    public DiagramImportController(DiagramImportService service) { this.service = service; }

    @PostMapping(value = "/xmi", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DiagramImportResult importXmi(@org.springframework.web.bind.annotation.PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file, @RequestParam Mode mode,
            @RequestParam(required = false) UUID diagramId, @RequestParam(required = false) UUID versionId,
            @AuthenticationPrincipal User user) {
        return service.importXmi(projectId, user.getId(), file, mode, diagramId, versionId);
    }
}

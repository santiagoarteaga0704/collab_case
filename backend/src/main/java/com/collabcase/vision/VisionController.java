package com.collabcase.vision;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.collabcase.user.User;
import com.collabcase.vision.dto.VisionExtractionResponse;

@RestController
@RequestMapping("/api/vision")
public class VisionController {
    private final VisionService service;

    public VisionController(VisionService service) {
        this.service = service;
    }

    @PostMapping(value = "/diagrams/{diagramId}/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VisionExtractionResponse extract(@PathVariable UUID diagramId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "instruction", required = false) String instruction,
            @AuthenticationPrincipal User user) {
        return service.extract(diagramId, user.getId(), file, instruction);
    }
}

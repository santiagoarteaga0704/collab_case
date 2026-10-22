package com.collabcase.ai;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.collabcase.ai.dto.AiAnalysisRequest;
import com.collabcase.ai.dto.AiAnalysisResponse;
import com.collabcase.user.User;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/diagrams/{diagramId}/analyze")
    public AiAnalysisResponse analyze(
            @PathVariable UUID diagramId,
            @Valid @RequestBody(required = false) AiAnalysisRequest request,
            @AuthenticationPrincipal User user) {
        return aiService.analyze(diagramId, user.getId(), request == null ? null : request.message());
    }
}

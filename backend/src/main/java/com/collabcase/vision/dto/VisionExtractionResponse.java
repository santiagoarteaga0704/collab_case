package com.collabcase.vision.dto;

import java.util.List;

import com.collabcase.ai.dto.AiSuggestion;

public record VisionExtractionResponse(String provider, String summary, List<ClassCandidate> classes,
        List<RelationshipCandidate> relationships, List<String> warnings, List<AiSuggestion> suggestions) {
}

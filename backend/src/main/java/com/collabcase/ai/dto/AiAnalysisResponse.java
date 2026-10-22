package com.collabcase.ai.dto;

import java.util.List;

public record AiAnalysisResponse(String message, List<AiSuggestion> suggestions, String provider) {
}

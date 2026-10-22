package com.collabcase.ai.dto;

import jakarta.validation.constraints.Size;

public record AiAnalysisRequest(@Size(max = 2000) String message) {
}

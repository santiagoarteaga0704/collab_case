package com.collabcase.ai.dto;

import java.util.List;

public record AiSuggestedMethod(String name, List<AiSuggestedParameter> parameters, String returnType) {
}

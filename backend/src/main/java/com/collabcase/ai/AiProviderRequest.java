package com.collabcase.ai;

import java.util.Map;

public record AiProviderRequest(String systemPrompt, String userPrompt, Map<String, Object> jsonSchema) {
}

package com.collabcase.vision;

import java.util.Map;

public record VisionProviderRequest(byte[] image, String contentType, String prompt,
        Map<String, Object> jsonSchema) {
}

package com.collabcase.diagram.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DiagramResponse(
        UUID id,
        String name,
        String type,
        Map<String, Object> model,
        Instant createdAt,
        Instant updatedAt) {
}

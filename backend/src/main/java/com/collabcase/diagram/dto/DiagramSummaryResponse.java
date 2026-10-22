package com.collabcase.diagram.dto;

import java.time.Instant;
import java.util.UUID;

public record DiagramSummaryResponse(
        UUID id,
        String name,
        String type,
        Instant createdAt,
        Instant updatedAt) {
}

package com.collabcase.project.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        String role,
        Instant createdAt,
        Instant updatedAt) {
}

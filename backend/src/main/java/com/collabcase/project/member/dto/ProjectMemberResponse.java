package com.collabcase.project.member.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberResponse(
        UUID userId,
        String name,
        String email,
        String role,
        Instant createdAt) {
}

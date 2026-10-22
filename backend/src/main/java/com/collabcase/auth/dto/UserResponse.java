package com.collabcase.auth.dto;

import java.util.UUID;

public record UserResponse(UUID id, String name, String email) {
}

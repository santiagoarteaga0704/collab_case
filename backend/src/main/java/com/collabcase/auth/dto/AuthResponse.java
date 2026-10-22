package com.collabcase.auth.dto;

public record AuthResponse(String token, UserResponse user) {
}

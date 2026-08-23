package com.examora.backend.auth.dto;

public record AuthResponse(
        String token,
        UserResponse user
) {
}

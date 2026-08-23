package com.examora.backend.auth.dto;

public record LoginRequest(
        String email,
        String password
) {
}

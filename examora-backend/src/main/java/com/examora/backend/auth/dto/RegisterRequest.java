package com.examora.backend.auth.dto;

public record RegisterRequest(
        String name,
        String email,
        String password
) {
}

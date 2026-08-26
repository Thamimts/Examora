package com.examora.dto;

import com.examora.model.User;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RegisterRequest(String name, String email, String password) {
    }

    public record AuthResponse(String token, User user) {
    }
}

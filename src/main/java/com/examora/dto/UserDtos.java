package com.examora.dto;

import com.examora.model.Role;

public final class UserDtos {
    private UserDtos() {
    }

    public record CreateUserRequest(String name, String email, String password, Role role) {
    }
}
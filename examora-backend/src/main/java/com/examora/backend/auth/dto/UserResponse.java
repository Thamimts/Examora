package com.examora.backend.auth.dto;

import com.examora.backend.user.User;

public record UserResponse(
        String id,
        String name,
        String email,
        String role
) {

    public static UserResponse from(User user) {

        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole().name()
        );
    }
}

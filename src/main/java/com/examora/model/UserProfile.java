package com.examora.model;

import java.time.LocalDate;

/**
 * Authenticated account profile including optional academic identity fields.
 * Identity fields are additive; accounts without an academic identity keep them null.
 */
public record UserProfile(
        String id,
        String name,
        String email,
        Role role,
        String avatar,
        String rollNumber,
        LocalDate dateOfBirth,
        String department,
        String batch,
        boolean passwordChangeRequired
) {
    public UserProfile(String id, String name, String email, Role role, String avatar) {
        this(id, name, email, role, avatar, null, null, null, null, false);
    }
}
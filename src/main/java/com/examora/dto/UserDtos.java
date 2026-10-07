package com.examora.dto;

import com.examora.model.Role;

public final class UserDtos {
    private UserDtos() {
    }

    /**
     * Admin-created account. When {@code role} is STUDENT and {@code rollNumber} is provided the
     * account is seeded as an academic identity: a password is optional, and when omitted the
     * student signs in once via roll number + date of birth and must set a password on first login.
     */
    public record CreateUserRequest(String name, String email, String password, Role role,
                                    String rollNumber, String dateOfBirth, String department, String batch) {
        public CreateUserRequest(String name, String email, String password, Role role) {
            this(name, email, password, role, null, null, null, null);
        }
    }

    /**
     * Self-service profile updates. Only the account owner (or an administrator) may call this,
     * and only for their own identity; role and email are not editable here.
     */
    public record UpdateProfileRequest(String name, String department, String batch, String avatar) {
    }

    /**
     * Administrator-reset of a student's academic credentials: registers the roll number and date
     * of birth and forces a first-login password change.
     */
    public record ResetStudentCredentialsRequest(String rollNumber, String dateOfBirth) {
    }
}
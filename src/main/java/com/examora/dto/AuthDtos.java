package com.examora.dto;

import com.examora.model.User;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(String email, String password, String rollNumber, String dateOfBirth) {
        public LoginRequest(String email, String password) {
            this(email, password, null, null);
        }
    }

    public record RegisterRequest(String name, String email, String password) {
    }

    public record AuthResponse(String token, User user) {
    }

    /**
     * Login response. For accounts without 2FA enabled this keeps the historical
     * shape ({@code token}/{@code user}); when 2FA is enabled the password step
     * returns {@code requiresTwoFactor=true} plus a single-use {@code challengeToken}
     * that must be exchanged for a token at the 2FA verify/recover endpoint.
     * When a student signs in via roll number + date of birth on first login,
     * {@code requiresPasswordChange} is {@code true} and the client must prompt a
     * password change before showing the workspace.
     * When an administrator signs in without two-factor authentication enabled the
     * response carries {@code setupRequired=true} and a short-lived {@code setupToken}
     * that is restricted to the 2FA setup/confirm endpoints. No normal access token is
     * issued until 2FA is enabled and a fresh sign-in is completed.
     */
    public record LoginResult(String token, User user, boolean requiresTwoFactor, String challengeToken,
                              boolean requiresPasswordChange, boolean setupRequired, String setupToken) {
        public LoginResult(String token, User user, boolean requiresTwoFactor, String challengeToken) {
            this(token, user, requiresTwoFactor, challengeToken, false, false, null);
        }

        public LoginResult(String token, User user, boolean requiresTwoFactor, String challengeToken,
                           boolean requiresPasswordChange) {
            this(token, user, requiresTwoFactor, challengeToken, requiresPasswordChange, false, null);
        }
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    public record TwoFactorVerifyRequest(String challengeToken, String code) {
    }

    public record TwoFactorRecoverRequest(String challengeToken, String recoveryCode) {
    }

    public record TwoFactorSetupResponse(String secret, String otpauthUri) {
    }

    public record TwoFactorConfirmRequest(String code) {
    }

    public record TwoFactorDisableRequest(String code, String password) {
    }

    public record TwoFactorStatusResponse(boolean enabled) {
    }

    public record RecoveryCodesResponse(java.util.List<String> recoveryCodes) {
    }
}

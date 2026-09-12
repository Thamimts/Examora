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

    /**
     * Login response. For accounts without 2FA enabled this keeps the historical
     * shape ({@code token}/{@code user}); when 2FA is enabled the password step
     * returns {@code requiresTwoFactor=true} plus a single-use {@code challengeToken}
     * that must be exchanged for a token at the 2FA verify/recover endpoint.
     */
    public record LoginResult(String token, User user, boolean requiresTwoFactor, String challengeToken) {
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

package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.AuthDtos.AuthResponse;
import com.examora.dto.AuthDtos.ChangePasswordRequest;
import com.examora.dto.AuthDtos.LoginRequest;
import com.examora.dto.AuthDtos.LoginResult;
import com.examora.dto.AuthDtos.RecoveryCodesResponse;
import com.examora.dto.AuthDtos.RegisterRequest;
import com.examora.dto.AuthDtos.TwoFactorConfirmRequest;
import com.examora.dto.AuthDtos.TwoFactorDisableRequest;
import com.examora.dto.AuthDtos.TwoFactorRecoverRequest;
import com.examora.dto.AuthDtos.TwoFactorSetupResponse;
import com.examora.dto.AuthDtos.TwoFactorStatusResponse;
import com.examora.dto.AuthDtos.TwoFactorVerifyRequest;
import com.examora.exception.ApiException;
import com.examora.exception.TooManyRequestsException;
import com.examora.model.User;
import com.examora.security.ClientIpResolver;
import com.examora.security.Permission;
import com.examora.service.AuthService;
import com.examora.service.AuthorizationService;
import com.examora.service.LoginRateLimiter;
import com.examora.service.TwoFactorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final TwoFactorService twoFactorService;
    private final AuthorizationService authorizationService;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService,
                          TwoFactorService twoFactorService,
                          AuthorizationService authorizationService,
                          LoginRateLimiter loginRateLimiter,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.twoFactorService = twoFactorService;
        this.authorizationService = authorizationService;
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        boolean academic = request.rollNumber() != null && !request.rollNumber().isBlank();
        String key = academic ? request.rollNumber().trim().toUpperCase() : (request.email() == null ? "" : request.email().trim().toLowerCase());
        String ip = clientIpResolver.resolve(http);
        if (!loginRateLimiter.isAllowed(ip, key)) {
            int retryAfter = loginRateLimiter.retryAfterSeconds(ip, key).orElse(1);
            throw new TooManyRequestsException("Too many login attempts. Please try again later.", retryAfter);
        }
        loginRateLimiter.recordAttempt(ip, key);
        try {
            if (academic) {
                return ApiResponse.ok(authService.academicFirstLogin(request.rollNumber(), request.dateOfBirth()));
            }
            return ApiResponse.ok(authService.login(request.email(), request.password()));
        } catch (ApiException exception) {
            if (exception.status() == HttpStatus.UNAUTHORIZED) {
                loginRateLimiter.recordFailure(ip, key);
            }
            throw exception;
        }
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ApiResponse.ok("Registered", authService.register(request.name(), request.email(), request.password()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        authService.logout(authorizationHeader);
        return ApiResponse.ok("Logged out", null);
    }

    @PostMapping("/2fa/verify")
    public ApiResponse<AuthResponse> verifyTwoFactor(@RequestBody TwoFactorVerifyRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.completeTwoFactor(
                request.challengeToken(),
                request.code(),
                clientIpResolver.resolve(http)));
    }

    @PostMapping("/2fa/recover")
    public ApiResponse<AuthResponse> recoverTwoFactor(@RequestBody TwoFactorRecoverRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.completeRecovery(
                request.challengeToken(),
                request.recoveryCode(),
                clientIpResolver.resolve(http)));
    }

    @GetMapping("/2fa/status")
    public ApiResponse<TwoFactorStatusResponse> twoFactorStatus(@RequestHeader("Authorization") String authorizationHeader) {
        User user = authorizationService.requireUser(authorizationHeader);
        return ApiResponse.ok(new TwoFactorStatusResponse(twoFactorService.status(user.id())));
    }

    @PostMapping("/2fa/setup")
    public ApiResponse<TwoFactorSetupResponse> setupTwoFactor(@RequestHeader("Authorization") String authorizationHeader) {
        User user = authorizationService.requirePermission(authorizationHeader, Permission.ACCOUNT_SELF);
        return ApiResponse.ok(twoFactorService.prepareSetup(user));
    }

    @PostMapping("/2fa/confirm")
    public ApiResponse<RecoveryCodesResponse> confirmTwoFactor(@RequestHeader("Authorization") String authorizationHeader,
                                                               @RequestBody TwoFactorConfirmRequest request) {
        User user = authorizationService.requirePermission(authorizationHeader, Permission.ACCOUNT_SELF);
        return ApiResponse.ok("Two-factor authentication enabled", twoFactorService.confirmSetup(user, request.code()));
    }

    @PostMapping("/2fa/disable")
    public ApiResponse<Void> disableTwoFactor(@RequestHeader("Authorization") String authorizationHeader,
                                              @RequestBody TwoFactorDisableRequest request,
                                              HttpServletRequest http) {
        User user = authorizationService.requirePermission(authorizationHeader, Permission.ACCOUNT_SELF);
        twoFactorService.disable(user, request.code(), request.password(), clientIpResolver.resolve(http));
        return ApiResponse.ok("Two-factor authentication disabled", null);
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestHeader("Authorization") String authorizationHeader,
                                            @RequestBody ChangePasswordRequest request) {
        User user = authorizationService.requirePermission(authorizationHeader, Permission.ACCOUNT_SELF);
        authService.changePassword(user, request.currentPassword(), request.newPassword());
        return ApiResponse.ok("Password changed", null);
    }
}
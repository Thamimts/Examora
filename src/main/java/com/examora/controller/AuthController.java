package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.AuthDtos.AuthResponse;
import com.examora.dto.AuthDtos.LoginRequest;
import com.examora.dto.AuthDtos.RegisterRequest;
import com.examora.exception.ApiException;
import com.examora.exception.TooManyRequestsException;
import com.examora.service.AuthService;
import com.examora.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        String ip = clientIp(http);
        if (!loginRateLimiter.isAllowed(ip, email)) {
            int retryAfter = loginRateLimiter.retryAfterSeconds(ip, email).orElse(1);
            throw new TooManyRequestsException("Too many login attempts. Please try again later.", retryAfter);
        }
        loginRateLimiter.recordAttempt(ip, email);
        try {
            return ApiResponse.ok(authService.login(request.email(), request.password()));
        } catch (ApiException exception) {
            if (exception.status() == org.springframework.http.HttpStatus.UNAUTHORIZED) {
                loginRateLimiter.recordFailure(ip, email);
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

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            if (!first.isBlank()) {
                return first.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}

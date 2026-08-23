package com.examora.backend.auth;

import com.examora.backend.auth.dto.*;
import com.examora.backend.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(
            AuthService authService
    ) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(
            @RequestBody RegisterRequest request
    ) {

        return ApiResponse.success(
                "Registration successful",
                authService.register(request)
        );
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @RequestBody LoginRequest request
    ) {

        return ApiResponse.success(
                "Login successful",
                authService.login(request)
        );
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {

        return ApiResponse.success(
                "Logout successful",
                null
        );
    }
}

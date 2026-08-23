package com.examora.controller;

import com.examora.dto.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

    @GetMapping("/api/status")
    public ApiResponse<Map<String, String>> status() {
        return ApiResponse.ok(Map.of("application", "Examora", "status", "running"));
    }
}

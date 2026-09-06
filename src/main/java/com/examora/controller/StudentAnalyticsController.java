package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.StudentPerformanceAnalytics;
import com.examora.model.User;
import com.examora.service.StudentAnalyticsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student")
public class StudentAnalyticsController {
    private final StudentAnalyticsService analyticsService;

    public StudentAnalyticsController(StudentAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/performance")
    public ApiResponse<StudentPerformanceAnalytics> performance(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(analyticsService.getPerformance(student.id()));
    }
}

package com.examora.controller;

import com.examora.dto.AnalyticsDtos.StudentAnalyticsSummary;
import com.examora.dto.AnalyticsDtos.StudentExamAnalytics;
import com.examora.dto.ApiResponse;
import com.examora.dto.StudentPerformanceAnalytics;
import com.examora.model.User;
import com.examora.service.AnalyticsService;
import com.examora.service.StudentAnalyticsService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student")
public class StudentAnalyticsController {
    private final StudentAnalyticsService analyticsService;
    private final AnalyticsService analytics;

    public StudentAnalyticsController(StudentAnalyticsService analyticsService, AnalyticsService analytics) {
        this.analyticsService = analyticsService;
        this.analytics = analytics;
    }

    @GetMapping("/performance")
    public ApiResponse<StudentPerformanceAnalytics> performance(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(analyticsService.getPerformance(student.id()));
    }

    @GetMapping("/analytics/summary")
    public ApiResponse<StudentAnalyticsSummary> summary(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(analytics.studentSummary(student.id()));
    }

    @GetMapping("/analytics/exams")
    public ApiResponse<List<StudentExamAnalytics>> exams(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(analytics.studentExams(student.id()));
    }
}
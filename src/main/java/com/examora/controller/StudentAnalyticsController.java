package com.examora.controller;

import com.examora.dto.AnalyticsDtos.StudentAnalyticsSummary;
import com.examora.dto.AnalyticsDtos.StudentExamAnalytics;
import com.examora.dto.ApiResponse;
import com.examora.dto.LearningIntelligenceDtos.LearningIntelligence;
import com.examora.dto.LearningProfileDtos.LearningProfile;
import com.examora.dto.StudentPerformanceAnalytics;
import com.examora.dto.StudentProgressDtos.StudentProgress;
import com.examora.dto.StudentRecommendationDtos.StudentRecommendations;
import com.examora.model.User;
import com.examora.service.AnalyticsService;
import com.examora.service.LearningIntelligenceService;
import com.examora.service.LearningProfileService;
import com.examora.service.StudentAnalyticsService;
import com.examora.service.StudentProgressService;
import com.examora.service.StudentRecommendationService;
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
    private final LearningProfileService learningProfileService;
    private final LearningIntelligenceService learningIntelligenceService;
    private final StudentProgressService studentProgressService;
    private final StudentRecommendationService studentRecommendationService;

    public StudentAnalyticsController(StudentAnalyticsService analyticsService, AnalyticsService analytics,
                                      LearningProfileService learningProfileService,
                                      LearningIntelligenceService learningIntelligenceService,
                                      StudentProgressService studentProgressService,
                                      StudentRecommendationService studentRecommendationService) {
        this.analyticsService = analyticsService;
        this.analytics = analytics;
        this.learningProfileService = learningProfileService;
        this.learningIntelligenceService = learningIntelligenceService;
        this.studentProgressService = studentProgressService;
        this.studentRecommendationService = studentRecommendationService;
    }

    @GetMapping("/performance")
    public ApiResponse<StudentPerformanceAnalytics> performance(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(analyticsService.getPerformance(student.id()));
    }

    @GetMapping("/learning-profile")
    public ApiResponse<LearningProfile> learningProfile(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        learningProfileService.requireStudent(student);
        return ApiResponse.ok(learningProfileService.build(student.id()));
    }

    @GetMapping("/learning-intelligence")
    public ApiResponse<LearningIntelligence> learningIntelligence(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        learningIntelligenceService.requireStudent(student);
        return ApiResponse.ok(learningIntelligenceService.build(student.id()));
    }

    @GetMapping("/progress")
    public ApiResponse<StudentProgress> progress(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        studentProgressService.requireStudent(student);
        return ApiResponse.ok(studentProgressService.build(student.id()));
    }

    @GetMapping("/recommendations")
    public ApiResponse<StudentRecommendations> recommendations(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        studentRecommendationService.requireStudent(student);
        return ApiResponse.ok(studentRecommendationService.build(student.id()));
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
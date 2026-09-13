package com.examora.controller;

import com.examora.dto.AnalyticsDtos.ExamAnalyticsDetail;
import com.examora.dto.AnalyticsDtos.ExamAnalyticsSummary;
import com.examora.dto.AnalyticsDtos.OptionLabelRow;
import com.examora.dto.AnalyticsDtos.StudentDrilldownAnalytics;
import com.examora.dto.AnalyticsDtos.StudentPerformanceRow;
import com.examora.dto.ApiResponse;
import com.examora.model.User;
import com.examora.service.AnalyticsService;
import com.examora.service.AuthService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService analyticsService;
    private final AuthService authService;

    public AnalyticsController(AnalyticsService analyticsService, AuthService authService) {
        this.analyticsService = analyticsService;
        this.authService = authService;
    }

    @GetMapping("/exams")
    public ApiResponse<List<ExamAnalyticsSummary>> examSummaries(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(analyticsService.examSummaries(actor));
    }

    @GetMapping("/exams/{examId}")
    public ApiResponse<ExamAnalyticsDetail> examDetail(
            @PathVariable String examId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(analyticsService.examDetail(examId, actor));
    }

    @GetMapping("/exams/{examId}/students")
    public ApiResponse<List<StudentPerformanceRow>> examStudents(
            @PathVariable String examId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(analyticsService.studentPerformance(examId, actor));
    }

    @GetMapping("/exams/{examId}/options")
    public ApiResponse<List<OptionLabelRow>> examOptionLabels(
            @PathVariable String examId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(analyticsService.optionLabels(examId, actor));
    }

    @GetMapping("/students/{studentId}")
    public ApiResponse<StudentDrilldownAnalytics> studentDrilldown(
            @PathVariable String studentId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(analyticsService.studentDrilldown(studentId, actor));
    }
}
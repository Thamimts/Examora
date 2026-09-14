package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ExamCoachDtos.CoachExamSummaryDto;
import com.examora.dto.ExamCoachDtos.ExamCoachContextDto;
import com.examora.dto.ExamCoachDtos.ExamCoachRequestDto;
import com.examora.model.User;
import com.examora.service.ExamCoachService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/ai-coach")
public class ExamCoachController {
    private final ExamCoachService examCoachService;

    public ExamCoachController(ExamCoachService examCoachService) {
        this.examCoachService = examCoachService;
    }

    @GetMapping("/exams")
    public ApiResponse<List<CoachExamSummaryDto>> exams(Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(examCoachService.completedExams(student));
    }

    @PostMapping("/exams/{examId}")
    public ApiResponse<ExamCoachContextDto> context(
            @PathVariable String examId,
            @RequestBody(required = false) ExamCoachRequestDto request,
            Authentication authentication) {
        User student = (User) authentication.getPrincipal();
        return ApiResponse.ok(examCoachService.context(student, examId, request));
    }
}
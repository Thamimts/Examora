package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.EnrollmentDtos.EnrollRequest;
import com.examora.dto.EnrollmentDtos.EnrollmentDetailDto;
import com.examora.dto.EnrollmentDtos.ExamRosterDto;
import com.examora.dto.EnrollmentDtos.RoomSeatingDto;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.service.AuthorizationService;
import com.examora.service.EnrollmentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/enrollments")
public class EnrollmentController {
    private final EnrollmentService enrollmentService;
    private final AuthorizationService authorizationService;

    public EnrollmentController(EnrollmentService enrollmentService, AuthorizationService authorizationService) {
        this.enrollmentService = enrollmentService;
        this.authorizationService = authorizationService;
    }

    @PostMapping
    public ApiResponse<EnrollmentDetailDto> enroll(@RequestBody EnrollRequest request,
                                                   @RequestHeader("Authorization") String authorizationHeader) {
        User student = authorizationService.requireRole(authorizationHeader, Role.STUDENT);
        return ApiResponse.ok("Enrolled", enrollmentService.enroll(student, request));
    }

    @GetMapping("/my")
    public ApiResponse<List<EnrollmentDetailDto>> mine(@RequestHeader("Authorization") String authorizationHeader) {
        User student = authorizationService.requireRole(authorizationHeader, Role.STUDENT);
        return ApiResponse.ok(enrollmentService.myEnrollments(student));
    }

    @GetMapping("/exam/{examId}")
    public ApiResponse<List<ExamRosterDto>> roster(@PathVariable String examId,
                                                   @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(enrollmentService.roster(examId, actor));
    }

    @GetMapping("/exam/{examId}/rooms")
    public ApiResponse<List<RoomSeatingDto>> examRooms(@PathVariable String examId,
                                                       @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(enrollmentService.examRooms(examId, actor));
    }

    @GetMapping("/exam/{examId}/rooms/{roomId}")
    public ApiResponse<RoomSeatingDto> room(@PathVariable String examId, @PathVariable String roomId,
                                            @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(enrollmentService.roomSeating(roomId, examId, actor));
    }

    @PutMapping("/exam/{examId}/students/{studentId}")
    public ApiResponse<ExamRosterDto> manualAssign(@PathVariable String examId, @PathVariable String studentId,
                                                   @RequestBody ManualSeatRequest request,
                                                   @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.ADMIN);
        ExamRosterDto updated = enrollmentService.manualAssign(examId, studentId, request.roomId(), request.seatNumber(), actor);
        return ApiResponse.ok("Assigned", updated);
    }

    public record ManualSeatRequest(String roomId, int seatNumber) {
    }
}
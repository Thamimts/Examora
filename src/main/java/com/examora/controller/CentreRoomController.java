package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.CentreDtos.CentreRoomRequest;
import com.examora.dto.CentreDtos.CentreRoomResponse;
import com.examora.dto.EnrollmentDtos.InvigilatorRoomSummaryDto;
import com.examora.dto.EnrollmentDtos.RoomSeatingDto;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.service.AuthorizationService;
import com.examora.service.EnrollmentService;
import com.examora.service.ExamCentreService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/centre-rooms")
public class CentreRoomController {
    private final ExamCentreService centreService;
    private final EnrollmentService enrollmentService;
    private final AuthorizationService authorizationService;

    public CentreRoomController(ExamCentreService centreService, EnrollmentService enrollmentService,
                                AuthorizationService authorizationService) {
        this.centreService = centreService;
        this.enrollmentService = enrollmentService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/mine")
    public ApiResponse<List<CentreRoomResponse>> mine(@RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(centreService.myInvigilatorRooms(actor));
    }

    @GetMapping("/mine/summary")
    public ApiResponse<List<InvigilatorRoomSummaryDto>> mineSummary(@RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(enrollmentService.invigilatorRoomSummaries(actor));
    }

    @GetMapping("/{roomId}/seating")
    public ApiResponse<RoomSeatingDto> seating(@PathVariable String roomId,
                                               @RequestParam(required = false) String examId,
                                               @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(enrollmentService.roomSeating(roomId, examId, actor));
    }

    @PostMapping
    public ApiResponse<CentreRoomResponse> create(@RequestBody CentreRoomRequest request,
                                                  @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.ADMIN);
        return ApiResponse.ok("Created", centreService.createRoom(request, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<CentreRoomResponse> update(@PathVariable String id, @RequestBody CentreRoomRequest request,
                                                  @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.ADMIN);
        return ApiResponse.ok("Updated", centreService.updateRoom(id, request, actor));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id,
                                    @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.ADMIN);
        centreService.deleteRoom(id, actor);
        return ApiResponse.ok("Deleted", null);
    }
}
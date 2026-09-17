package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.ExamRoomDtos.CreateRoomRequest;
import com.examora.dto.ExamRoomDtos.ExamRoomDto;
import com.examora.dto.ExamRoomDtos.JoinRoomRequest;
import com.examora.dto.ExamRoomDtos.JoinRoomResponse;
import com.examora.dto.ExamRoomDtos.RoomDetailDto;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.ExamRoomService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exam-rooms")
public class ExamRoomController {
    private final ExamRoomService examRoomService;
    private final AuthService authService;

    public ExamRoomController(ExamRoomService examRoomService, AuthService authService) {
        this.examRoomService = examRoomService;
        this.authService = authService;
    }

    @PostMapping
    public ApiResponse<ExamRoomDto> create(@jakarta.validation.Valid @RequestBody CreateRoomRequest request,
                                           @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Created", examRoomService.create(request, authService.requireUser(authorizationHeader)));
    }

    @GetMapping("/my")
    public ApiResponse<List<ExamRoomDto>> myRooms(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok(examRoomService.myRooms(authService.requireUser(authorizationHeader)));
    }

    @GetMapping("/{roomId}")
    public ApiResponse<RoomDetailDto> get(@PathVariable String roomId,
                                          @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok(examRoomService.get(roomId, authService.requireUser(authorizationHeader)));
    }

    @PostMapping("/{roomId}/start")
    public ApiResponse<ExamRoomDto> start(@PathVariable String roomId,
                                          @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Started", examRoomService.start(roomId, authService.requireUser(authorizationHeader)));
    }

    @PostMapping("/{roomId}/end")
    public ApiResponse<ExamRoomDto> end(@PathVariable String roomId,
                                        @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok("Ended", examRoomService.end(roomId, authService.requireUser(authorizationHeader)));
    }

    @PostMapping("/join")
    public ApiResponse<JoinRoomResponse> join(@jakarta.validation.Valid @RequestBody JoinRoomRequest request,
                                              @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User student = authService.requireUser(authorizationHeader);
        return ApiResponse.ok("Joined", examRoomService.join(request, student));
    }
}
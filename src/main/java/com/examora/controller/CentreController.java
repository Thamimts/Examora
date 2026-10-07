package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.CentreDtos.CentreRequest;
import com.examora.dto.CentreDtos.CentreResponse;
import com.examora.dto.CentreDtos.CentreRoomResponse;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.service.AuthorizationService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/centres")
public class CentreController {
    private final ExamCentreService centreService;
    private final AuthorizationService authorizationService;

    public CentreController(ExamCentreService centreService, AuthorizationService authorizationService) {
        this.centreService = centreService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ApiResponse<List<CentreResponse>> list(@RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(centreService.centres(actor));
    }

    @GetMapping("/{id}")
    public ApiResponse<CentreResponse> get(@PathVariable String id,
                                           @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(centreService.centre(id, actor));
    }

    @GetMapping("/{id}/rooms")
    public ApiResponse<List<CentreRoomResponse>> rooms(@PathVariable String id,
                                                       @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.TEACHER, Role.ADMIN);
        return ApiResponse.ok(centreService.roomsForCentre(id, actor));
    }

    @PostMapping
    public ApiResponse<CentreResponse> create(@RequestBody CentreRequest request,
                                              @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.ADMIN);
        return ApiResponse.ok("Created", centreService.createCentre(request, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<CentreResponse> update(@PathVariable String id, @RequestBody CentreRequest request,
                                              @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.ADMIN);
        return ApiResponse.ok("Updated", centreService.updateCentre(id, request, actor));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id,
                                    @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requireRole(authorizationHeader, Role.ADMIN);
        centreService.deleteCentre(id, actor);
        return ApiResponse.ok("Deleted", null);
    }
}
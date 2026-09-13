package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.exception.ApiException;
import com.examora.model.Result;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.ResultService;
import java.util.List;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/results")
public class ResultController {
    private final ResultService resultService;
    private final AuthService authService;

    public ResultController(ResultService resultService, AuthService authService) {
        this.resultService = resultService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<Result>> list(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(resultService.findAllForStaff(actor));
    }

    @GetMapping("/me")
    public ApiResponse<List<Result>> mine(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(required = false) String userId) {
        User user = authService.requireUser(authorizationHeader);
        if (user.role() == Role.STUDENT) {
            return ApiResponse.ok(resultService.findByUserId(user.id()));
        }
        if (userId == null || userId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "User id is required for teacher or administrator access.");
        }
        return ApiResponse.ok(resultService.findByUserIdForStaff(userId, user));
    }

    @GetMapping("/{id}")
    public ApiResponse<Result> get(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok(resultService.findByIdForStaff(id, actor));
    }

    @PostMapping
    public ApiResponse<Result> create(
            @RequestBody Result result,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok("Created", resultService.create(result, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<Result> update(
            @PathVariable String id,
            @RequestBody Result result,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        return ApiResponse.ok("Updated", resultService.update(id, result, actor));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        User actor = authService.requireUser(authorizationHeader);
        resultService.delete(id, actor);
        return ApiResponse.ok("Deleted", null);
    }
}
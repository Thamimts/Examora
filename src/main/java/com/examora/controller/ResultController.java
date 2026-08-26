package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.Result;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.ResultService;
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
@RequestMapping("/api/results")
public class ResultController {
    private final ResultService resultService;
    private final AuthService authService;

    public ResultController(ResultService resultService, AuthService authService) {
        this.resultService = resultService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<Result>> list() {
        return ApiResponse.ok(resultService.findAll());
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
            return ApiResponse.ok(resultService.findAll());
        }
        return ApiResponse.ok(resultService.findByUserId(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Result> get(@PathVariable String id) {
        return ApiResponse.ok(resultService.findById(id));
    }

    @PostMapping
    public ApiResponse<Result> create(@RequestBody Result result) {
        return ApiResponse.ok("Created", resultService.create(result));
    }

    @PutMapping("/{id}")
    public ApiResponse<Result> update(@PathVariable String id, @RequestBody Result result) {
        return ApiResponse.ok("Updated", resultService.update(id, result));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        resultService.delete(id);
        return ApiResponse.ok("Deleted", null);
    }
}

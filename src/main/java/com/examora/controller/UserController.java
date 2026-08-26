package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.User;
import com.examora.service.AuthService;
import com.examora.service.UserService;
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
@RequestMapping("/api/users")
public class UserController {
    private final AuthService authService;
    private final UserService userService;

    public UserController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<User>> list() {
        return ApiResponse.ok(userService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<User> get(@PathVariable String id) {
        return ApiResponse.ok(userService.findById(id));
    }

    @GetMapping("/me")
    public ApiResponse<User> me(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok(authService.requireUser(authorizationHeader));
    }

    @PostMapping
    public ApiResponse<User> create(@RequestBody User user) {
        return ApiResponse.ok("Created", userService.create(user));
    }

    @PutMapping("/{id}")
    public ApiResponse<User> update(@PathVariable String id, @RequestBody User user) {
        return ApiResponse.ok("Updated", userService.update(id, user));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return ApiResponse.ok("Deleted", null);
    }
}

package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.dto.UserDtos.CreateUserRequest;
import com.examora.model.User;
import com.examora.security.Permission;
import com.examora.service.ActivityService;
import com.examora.service.AuthService;
import com.examora.service.AuthorizationService;
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
    private final AuthorizationService authorizationService;
    private final ActivityService activityService;
    private final UserService userService;

    public UserController(AuthService authService, AuthorizationService authorizationService,
                          ActivityService activityService, UserService userService) {
        this.authService = authService;
        this.authorizationService = authorizationService;
        this.activityService = activityService;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<List<User>> list(@RequestHeader("Authorization") String authorizationHeader) {
        authorizationService.requirePermission(authorizationHeader, Permission.USER_MANAGE);
        return ApiResponse.ok(userService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<User> get(@PathVariable String id, @RequestHeader("Authorization") String authorizationHeader) {
        authorizationService.requirePermission(authorizationHeader, Permission.USER_MANAGE);
        return ApiResponse.ok(userService.findById(id));
    }

    @GetMapping("/me")
    public ApiResponse<User> me(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        return ApiResponse.ok(authService.requireUser(authorizationHeader));
    }

    @PostMapping
    public ApiResponse<User> create(@RequestBody CreateUserRequest request, @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requirePermission(authorizationHeader, Permission.USER_MANAGE);
        User created = userService.create(request);
        activityService.admin(actor, "USER_CREATED", actor.name() + " created " + created.name() + "'s account.");
        return ApiResponse.ok("Created", created);
    }

    @PutMapping("/{id}")
    public ApiResponse<User> update(@PathVariable String id, @RequestBody User user, @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requirePermission(authorizationHeader, Permission.USER_MANAGE);
        User updated = userService.update(id, user);
        activityService.admin(actor, "USER_UPDATED", actor.name() + " updated " + updated.name() + "'s account.");
        return ApiResponse.ok("Updated", updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, @RequestHeader("Authorization") String authorizationHeader) {
        User actor = authorizationService.requirePermission(authorizationHeader, Permission.USER_MANAGE);
        userService.delete(id);
        activityService.admin(actor, "USER_DELETED", actor.name() + " deleted an account.");
        return ApiResponse.ok("Deleted", null);
    }
}

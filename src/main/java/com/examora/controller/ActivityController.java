package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.ActivityEvent;
import com.examora.service.ActivityService;
import com.examora.service.AuthService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activity")
public class ActivityController {
    private final ActivityService activityService; private final AuthService authService;
    public ActivityController(ActivityService activityService, AuthService authService) { this.activityService = activityService; this.authService = authService; }
    @GetMapping
    public ApiResponse<List<ActivityEvent>> recent(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.ok(activityService.recent(authService.requireUser(authorization)));
    }
}

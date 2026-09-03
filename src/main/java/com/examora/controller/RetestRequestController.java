package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.RetestRequest;
import com.examora.service.AuthService;
import com.examora.service.RetestRequestService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/retest-requests")
public class RetestRequestController {
 private final AuthService auth; private final RetestRequestService service;
 public RetestRequestController(AuthService auth,RetestRequestService service){this.auth=auth;this.service=service;}
 @GetMapping public ApiResponse<List<RetestRequest>> mine(@RequestHeader(value="Authorization",required=false) String h){return ApiResponse.ok(service.mine(auth.requireUser(h)));}
 @PostMapping("/{examId}") public ApiResponse<RetestRequest> create(@PathVariable String examId,@RequestHeader(value="Authorization",required=false) String h){return ApiResponse.ok("Requested",service.create(examId,auth.requireUser(h)));}
 @GetMapping("/admin") public ApiResponse<List<RetestRequest>> all(@RequestHeader(value="Authorization",required=false) String h){return ApiResponse.ok(service.all(auth.requireAdmin(h)));}
 @PostMapping("/admin/{id}/review") public ApiResponse<RetestRequest> review(@PathVariable String id,@RequestBody Map<String,String> body,@RequestHeader(value="Authorization",required=false) String h){return ApiResponse.ok("Reviewed",service.review(id,body.get("status"),body.get("reason"),auth.requireAdmin(h)));}
}

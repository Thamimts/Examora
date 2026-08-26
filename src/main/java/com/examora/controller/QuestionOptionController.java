package com.examora.controller;

import com.examora.dto.ApiResponse;
import com.examora.model.QuestionOption;
import com.examora.service.QuestionOptionService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuestionOptionController {
    private final QuestionOptionService optionService;

    public QuestionOptionController(QuestionOptionService optionService) {
        this.optionService = optionService;
    }

    @GetMapping("/api/options")
    public ApiResponse<List<QuestionOption>> list() {
        return ApiResponse.ok(optionService.findAll());
    }

    @GetMapping("/api/options/{id}")
    public ApiResponse<QuestionOption> get(@PathVariable String id) {
        return ApiResponse.ok(optionService.findById(id));
    }

    @GetMapping("/api/questions/{questionId}/options")
    public ApiResponse<List<QuestionOption>> listByQuestion(@PathVariable String questionId) {
        return ApiResponse.ok(optionService.findByQuestionId(questionId));
    }

    @PostMapping("/api/options")
    public ApiResponse<QuestionOption> create(@RequestBody QuestionOption option) {
        return ApiResponse.ok("Created", optionService.create(option));
    }

    @PostMapping("/api/questions/{questionId}/options")
    public ApiResponse<QuestionOption> createForQuestion(@PathVariable String questionId, @RequestBody QuestionOption option) {
        return ApiResponse.ok("Created", optionService.create(questionId, option));
    }

    @PutMapping("/api/options/{id}")
    public ApiResponse<QuestionOption> update(@PathVariable String id, @RequestBody QuestionOption option) {
        return ApiResponse.ok("Updated", optionService.update(id, option));
    }

    @DeleteMapping("/api/options/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        optionService.delete(id);
        return ApiResponse.ok("Deleted", null);
    }
}

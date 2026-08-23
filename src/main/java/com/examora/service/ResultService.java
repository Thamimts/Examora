package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Result;
import com.examora.repository.ResultRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResultService {
    private final ResultRepository resultRepository;

    public ResultService(ResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    public List<Result> findAll() {
        return resultRepository.findAll();
    }

    public Result findById(String id) {
        return resultRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Result not found."));
    }

    public List<Result> findByUserId(String userId) {
        return resultRepository.findByUserId(userId);
    }

    public Result create(Result result) {
        Result normalized = normalize(result.id(), result);
        return resultRepository.create(normalized);
    }

    public Result update(String id, Result result) {
        findById(id);
        resultRepository.update(id, normalize(id, result));
        return findById(id);
    }

    public void delete(String id) {
        if (resultRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Result not found.");
        }
    }

    private Result normalize(String id, Result result) {
        return new Result(
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                blankToNull(result.userId()),
                blankToNull(result.examId()),
                required(result.examTitle(), "Exam title"),
                required(result.subject(), "Subject"),
                Math.max(result.score(), 0),
                result.date() == null || result.date().isBlank() ? LocalDate.now().toString() : result.date().trim(),
                result.total() <= 0 ? 100 : result.total());
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

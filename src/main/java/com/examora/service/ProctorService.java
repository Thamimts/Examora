package com.examora.service;

import com.examora.model.ProctorEvent;
import com.examora.model.ExamAttempt;
import com.examora.model.User;
import com.examora.repository.ProctorRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProctorService {
    private final ProctorRepository proctorRepository;
    private final ExamAttemptService examAttemptService;

    public ProctorService(ProctorRepository proctorRepository, ExamAttemptService examAttemptService) {
        this.proctorRepository = proctorRepository;
        this.examAttemptService = examAttemptService;
    }

    public int saveBatch(List<ProctorEvent> events) {
        return proctorRepository.saveBatch(events);
    }

    public int saveBatch(List<ProctorEvent> events, User actor) {
        if (events == null || events.isEmpty()) return 0;
        if (events.size() > 100) throw new com.examora.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "A proctor batch may contain at most 100 events.");
        String attemptId = events.getFirst() == null ? null : events.getFirst().attemptId();
        ExamAttempt attempt = examAttemptService.requireOwnedAttempt(attemptId, actor);
        for (ProctorEvent event : events) {
            if (event == null || !attempt.id().equals(event.attemptId()) || event.type() == null || event.type().isBlank() || event.type().length() > 80) {
                throw new com.examora.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Each proctor event needs a valid attempt and type.");
            }
        }
        return proctorRepository.saveBatch(events);
    }
}

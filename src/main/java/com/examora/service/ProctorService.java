package com.examora.service;

import com.examora.dto.ProctorDtos.ProctorEventDto;
import com.examora.dto.ProctorDtos.ProctorMonitorData;
import com.examora.dto.ProctorDtos.ProctorSummary;
import com.examora.exception.ApiException;
import com.examora.model.ExamAttempt;
import com.examora.model.ProctorEvent;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.ProctorRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProctorService {
    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "TAB_SWITCH",
            "WINDOW_BLUR",
            "CAMERA_OFF",
            "MULTIPLE_FACES",
            "AUDIO_DETECTED",
            "NETWORK_INTERRUPTION",
            "FULLSCREEN_EXIT");
    private static final int MAX_METADATA_JSON_LENGTH = 4096;
    private static final int MAX_OCCURRED_AT_LENGTH = 40;

    private final ProctorRepository proctorRepository;
    private final ExamAttemptService examAttemptService;
    private final ProctorMonitorService proctorMonitorService;
    private final ProctorPublishService proctorPublishService;
    private final ObjectMapper objectMapper;

    public ProctorService(ProctorRepository proctorRepository, ExamAttemptService examAttemptService,
                          ProctorMonitorService proctorMonitorService, ProctorPublishService proctorPublishService,
                          ObjectMapper objectMapper) {
        this.proctorRepository = proctorRepository;
        this.examAttemptService = examAttemptService;
        this.proctorMonitorService = proctorMonitorService;
        this.proctorPublishService = proctorPublishService;
        this.objectMapper = objectMapper;
    }

    public int saveBatch(List<ProctorEvent> events, User actor) {
        if (events == null || events.isEmpty()) return 0;
        if (events.size() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "A proctor batch may contain at most 100 events.");
        String attemptId = events.getFirst() == null ? null : events.getFirst().attemptId();
        if (attemptId == null || attemptId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attempt id is required.");
        }
        ExamAttempt attempt;
        if (actor != null && actor.role() == Role.STUDENT) {
            attempt = examAttemptService.requireOwnedAttempt(attemptId.trim(), actor);
        } else {
            attempt = examAttemptService.requireProctorAccess(attemptId.trim(), actor);
        }
        for (ProctorEvent event : events) {
            validateEvent(event, attempt);
        }
        int saved = proctorRepository.saveBatch(events);
        if (saved > 0) {
            proctorPublishService.publishAfterEventBatch(attempt.id());
        }
        return saved;
    }

    private void validateEvent(ProctorEvent event, ExamAttempt attempt) {
        if (event == null || !attempt.id().equals(event.attemptId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Each proctor event needs a valid attempt.");
        }
        String type = event.type();
        if (type == null || type.isBlank() || type.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Each proctor event needs a valid type.");
        }
        if (!ALLOWED_EVENT_TYPES.contains(type.trim().toUpperCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported proctor event type: " + type.trim() + ".");
        }
        validateOccurredAt(event.occurredAt());
        validateMetadata(event.metadata());
    }

    private void validateOccurredAt(String occurredAt) {
        if (occurredAt == null || occurredAt.isBlank()) return;
        if (occurredAt.length() > MAX_OCCURRED_AT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor event timestamp is invalid.");
        }
        try {
            Instant.parse(occurredAt.trim());
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor event timestamp must be an ISO-8601 instant.");
        }
    }

    private void validateMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.length() > MAX_METADATA_JSON_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor event metadata is too large.");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor event metadata must be valid JSON.");
        }
    }

    public ProctorMonitorData monitor(String examId, User actor) {
        return proctorMonitorService.monitor(examId, actor);
    }

    public ProctorSummary summary(String examId, User actor) {
        return proctorMonitorService.summary(examId, actor);
    }

    public List<ProctorEventDto> events(String attemptId, User actor, int limit) {
        return proctorMonitorService.events(attemptId, actor, limit);
    }
}
package com.examora.service;

import com.examora.dto.ProctorDtos.SubmitSignalRequest;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProctorSignalService {
    private static final Set<String> ALLOWED_SIGNAL_TYPES = Stream.concat(
            ProctorSignalTypes.FUTURE_AI_SIGNAL_TYPES.stream(),
            Stream.of("TAB_SWITCH", "WINDOW_BLUR", "CAMERA_OFF", "MULTIPLE_FACES",
                    "AUDIO_DETECTED", "NETWORK_INTERRUPTION", "FULLSCREEN_EXIT"))
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> VALID_SOURCES = Set.of(
            "BROWSER", "CLIENT_AI", "SERVER_AI", "HUMAN_INVIGILATOR", "SYSTEM");
    private static final Set<String> STUDENT_SOURCES = Set.of("BROWSER", "CLIENT_AI");
    private static final int MAX_TYPE_LENGTH = 80;
    private static final int MAX_METADATA_JSON_LENGTH = 2048;
    private static final int MAX_VALUE_STRING_LENGTH = 512;
    private static final int MAX_OCCURRED_AT_LENGTH = 40;
    private static final long MAX_DURATION_MS = 86_400_000L;
    private static final Set<String> BLOCKED_METADATA_KEYS = Set.of(
            "frame", "image", "video", "raw", "base64", "jwt", "token", "password", "secret", "key", "credential");
    private static final List<String> DATA_URI_PREFIXES = List.of(
            "data:image/", "data:video/", "data:audio/");

    private final ProctorRepository proctorRepository;
    private final ExamAttemptService examAttemptService;
    private final ProctorEnforcementService enforcementService;
    private final ProctorPublishService proctorPublishService;
    private final ObjectMapper objectMapper;

    public ProctorSignalService(ProctorRepository proctorRepository, ExamAttemptService examAttemptService,
                                ProctorEnforcementService enforcementService,
                                ProctorPublishService proctorPublishService, ObjectMapper objectMapper) {
        this.proctorRepository = proctorRepository;
        this.examAttemptService = examAttemptService;
        this.enforcementService = enforcementService;
        this.proctorPublishService = proctorPublishService;
        this.objectMapper = objectMapper;
    }

    public int submitSignal(String attemptId, SubmitSignalRequest request, User actor) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A proctor signal is required.");
        }
        if (isBlank(attemptId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Attempt id is required.");
        }
        ExamAttempt attempt;
        if (actor != null && actor.role() == Role.STUDENT) {
            attempt = examAttemptService.requireOwnedAttempt(attemptId.trim(), actor);
        } else {
            attempt = examAttemptService.requireProctorAccess(attemptId.trim(), actor);
        }

        String signalId = requireValue(request.signalId(), "Signal id is required.");
        String type = requireValue(request.signalType(), "Signal type is required.").toUpperCase();
        if (type.length() > MAX_TYPE_LENGTH || !ALLOWED_SIGNAL_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported proctor signal type: " + request.signalType().trim() + ".");
        }
        String source = resolveSource(request.source(), actor);
        validateConfidence(request.confidence(), source);
        validateOccurredAt(request.occurredAt());
        validateDurationMs(request.durationMs());
        validateMetadata(request.metadata());

        ProctorEvent signal = new ProctorEvent(signalId, attempt.id(), type, request.occurredAt(),
                request.metadata(), source, request.confidence(), request.durationMs());
        List<ProctorEvent> saved = proctorRepository.saveBatch(List.of(signal));
        if (!saved.isEmpty()) {
            enforcementService.enforce(saved);
            proctorPublishService.publishAfterEventBatch(attempt.id());
        }
        return saved.size();
    }

    private String resolveSource(String source, User actor) {
        String resolved = isBlank(source) ? "BROWSER" : source.trim().toUpperCase();
        if (!VALID_SOURCES.contains(resolved)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Invalid proctor signal source" + (isBlank(source) ? "." : ": " + source.trim() + "."));
        }
        if (actor != null && actor.role() == Role.STUDENT && !STUDENT_SOURCES.contains(resolved)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Students may only report BROWSER or CLIENT_AI signals.");
        }
        return resolved;
    }

    private void validateConfidence(Double confidence, String source) {
        boolean aiSource = "CLIENT_AI".equals(source) || "SERVER_AI".equals(source);
        if (aiSource && confidence == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Confidence is required for AI signals.");
        }
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Confidence must be a finite value between 0 and 1.");
        }
    }

    private void validateOccurredAt(String occurredAt) {
        if (isBlank(occurredAt)) return;
        if (occurredAt.trim().length() > MAX_OCCURRED_AT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal timestamp is invalid.");
        }
        try {
            Instant.parse(occurredAt.trim());
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal timestamp must be an ISO-8601 instant.");
        }
    }

    private void validateDurationMs(Long durationMs) {
        if (durationMs != null && (durationMs < 0 || durationMs > MAX_DURATION_MS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal duration is invalid.");
        }
    }

    private void validateMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.length() > MAX_METADATA_JSON_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal metadata is too large.");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal metadata must be valid JSON.");
        }
        walkMetadata(metadata);
    }

    private void walkMetadata(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? null : String.valueOf(entry.getKey()).toLowerCase();
                if (key != null && BLOCKED_METADATA_KEYS.contains(key)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal metadata contains a reserved field.");
                }
                walkMetadata(entry.getValue());
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walkMetadata(item);
            }
        } else if (node instanceof String value) {
            if (value.length() > MAX_VALUE_STRING_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal metadata contains an oversized value.");
            }
            String lower = value.toLowerCase();
            for (String prefix : DATA_URI_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Proctor signal metadata may not contain media payloads.");
                }
            }
        }
    }

    private String requireValue(String value, String message) {
        if (isBlank(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
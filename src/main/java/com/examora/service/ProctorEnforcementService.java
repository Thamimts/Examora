package com.examora.service;

import com.examora.model.ExamAttempt;
import com.examora.model.ProctorEvent;
import com.examora.repository.ExamAccessRepository;
import com.examora.repository.ExamAttemptRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProctorEnforcementService {
    public static final int MAX_WARNINGS = 3;
    public static final String TERMINATION_REASON = "PROCTOR_WARNING_LIMIT_REACHED";

    private static final Set<String> QUALIFYING_VIOLATION_TYPES = Set.of(
            "TAB_SWITCH",
            "WINDOW_BLUR",
            "CAMERA_OFF",
            "MULTIPLE_FACES",
            "AUDIO_DETECTED",
            "FULLSCREEN_EXIT");

    private final ExamAttemptRepository attempts;
    private final ExamAccessRepository access;

    public ProctorEnforcementService(ExamAttemptRepository attempts, ExamAccessRepository access) {
        this.attempts = attempts;
        this.access = access;
    }

    @Transactional
    public void enforce(List<ProctorEvent> savedEvents) {
        if (savedEvents == null || savedEvents.isEmpty()) {
            return;
        }
        for (ProctorEvent event : savedEvents) {
            if (event == null || !qualifies(event.type())) {
                continue;
            }
            String attemptId = event.attemptId();
            if (attempts.incrementWarningCount(attemptId, MAX_WARNINGS) != 1) {
                continue;
            }
            int count = attempts.warningCount(attemptId).orElse(MAX_WARNINGS);
            if (count < MAX_WARNINGS) {
                continue;
            }
            attempts.markProctorTerminated(attemptId, TERMINATION_REASON, Instant.now(), MAX_WARNINGS);
            ExamAttempt attempt = attempts.findById(attemptId).orElse(null);
            if (attempt != null) {
                access.suspend(attempt.studentId(), attempt.examId(), TERMINATION_REASON, Instant.now());
            }
        }
    }

    private boolean qualifies(String type) {
        return type != null && QUALIFYING_VIOLATION_TYPES.contains(type.trim().toUpperCase());
    }
}
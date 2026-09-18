package com.examora.service;

import com.examora.model.ExamAttempt;
import com.examora.repository.ResearchRepository.ResearchSample;
import java.time.Instant;
import java.util.function.Function;

/**
 * Pure post-hoc sample integrity checks shared by the experiment runner and the
 * research-run data-quality reporter. Flags samples whose window falls outside their
 * attempt's span, or whose window overlaps another sample of the same experiment and
 * attempt context. Deterministic and bounded (pairwise over the experiment's samples).
 */
public final class ResearchSampleValidity {

    private ResearchSampleValidity() {}

    /**
     * Returns a boolean array parallel to {@code samples}; true means the sample is invalid.
     */
    public static boolean[] markInvalid(java.util.List<ResearchSample> samples,
                                        Function<String, ExamAttempt> attemptLoader) {
        boolean[] invalid = new boolean[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            ResearchSample sample = samples.get(i);
            if (sample.attemptId() != null) {
                ExamAttempt attempt = attemptLoader.apply(sample.attemptId());
                if (attempt == null || attempt.startedAt() == null || attempt.expiresAt() == null
                        || outsideAttemptSpan(sample, attempt)) {
                    invalid[i] = true;
                }
            }
        }
        for (int i = 0; i < samples.size(); i++) {
            for (int j = i + 1; j < samples.size(); j++) {
                ResearchSample a = samples.get(i);
                ResearchSample b = samples.get(j);
                boolean sameContext = a.attemptId() == null
                        ? b.attemptId() == null : a.attemptId().equals(b.attemptId());
                if (sameContext && ResearchValidation.hasOverlap(
                        ResearchValidation.parseTimestamp(a.windowStart(), "windowStart"),
                        ResearchValidation.parseTimestamp(a.windowEnd(), "windowEnd"),
                        ResearchValidation.parseTimestamp(b.windowStart(), "windowStart"),
                        ResearchValidation.parseTimestamp(b.windowEnd(), "windowEnd"))) {
                    invalid[i] = true;
                    invalid[j] = true;
                }
            }
        }
        return invalid;
    }

    private static boolean outsideAttemptSpan(ResearchSample sample, ExamAttempt attempt) {
        Instant start = ResearchValidation.parseTimestamp(sample.windowStart(), "windowStart");
        Instant end = ResearchValidation.parseTimestamp(sample.windowEnd(), "windowEnd");
        try {
            ResearchValidation.validateAttemptWindow(start, end, attempt.startedAt(), attempt.expiresAt());
            return false;
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
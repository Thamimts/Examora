package com.examora.service;

import com.examora.dto.LearningIntelligenceDtos.Confidence;
import com.examora.dto.LearningIntelligenceDtos.DataQuality;
import com.examora.dto.LearningIntelligenceDtos.Dimension;
import com.examora.dto.LearningIntelligenceDtos.LearningIntelligence;
import com.examora.dto.LearningIntelligenceDtos.Priority;
import com.examora.dto.LearningIntelligenceDtos.PriorityLevel;
import com.examora.dto.LearningIntelligenceDtos.Recommendation;
import com.examora.dto.LearningIntelligenceDtos.RecommendationCode;
import com.examora.dto.LearningIntelligenceDtos.Severity;
import com.examora.dto.LearningIntelligenceDtos.Strength;
import com.examora.dto.LearningIntelligenceDtos.Weakness;
import com.examora.dto.LearningProfileDtos.DifficultyLevel;
import com.examora.dto.LearningProfileDtos.LearningProfile;
import com.examora.model.User;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Deterministic Student Strength &amp; Weakness Intelligence Engine.
 *
 * <p>Every result is a pure function of the server-authoritative {@link LearningProfile} produced
 * by {@link LearningProfileService}. No AI, no LLM, no fabricated topics/skills. All thresholds,
 * sample sizes and weights are defined as named constants below so the model is explainable.
 */
@Service
public class LearningIntelligenceService {

    // ----- accuracy thresholds (percentage points) -----
    /** At or above this accuracy a dimension is a strength. */
    private static final double STRENGTH_ACCURACY_PERCENT = 80.0;
    /** Documented positive tier (70-79): noticeably good but not emitted as a strength. */
    private static final double POSITIVE_ACCURACY_PERCENT = 70.0;
    /** At or below this accuracy a dimension is a weakness. */
    private static final double WEAKNESS_ACCURACY_PERCENT = 60.0;
    /** Below this accuracy a weakness is severity MEDIUM. */
    private static final double MODERATE_WEAKNESS_PERCENT = 50.0;
    /** Below this accuracy a weakness is severity HIGH (severe). */
    private static final double SEVERE_WEAKNESS_PERCENT = 35.0;
    /** Reference accuracy used to compute the performance gap in the priority model. */
    private static final double TARGET_ACCURACY_PERCENT = 80.0;

    // ----- sample sizes -----
    /** Minimum observations before any strength/weakness is emitted. */
    private static final int MIN_OBSERVATIONS = 5;
    /** Minimum completed exams before a consistency signal can be derived. */
    private static final int MIN_EXAMS_FOR_CONSISTENCY = 3;
    /** A declining recent delta beyond this magnitude is severity HIGH (else MEDIUM). */
    private static final double SEVERE_DECLINE_DELTA_PERCENT = 10.0;

    // ----- confidence bands (observation counts) -----
    private static final int CONFIDENCE_MEDIUM_MIN = 10;
    private static final int CONFIDENCE_HIGH_MIN = 20;
    /** Confidence bands when the observation unit is a completed exam (not an answer). */
    private static final int CONFIDENCE_MEDIUM_MIN_EXAMS = 6;
    private static final int CONFIDENCE_HIGH_MIN_EXAMS = 12;

    // ----- priority score weights -----
    private static final int SEVERITY_WEIGHT_HIGH = 40;
    private static final int SEVERITY_WEIGHT_MEDIUM = 25;
    private static final int SEVERITY_WEIGHT_LOW = 10;
    /** Performance gap contribution is capped so one factor cannot dominate. */
    private static final int GAP_CAP = 30;
    private static final int CONFIDENCE_WEIGHT_HIGH = 15;
    private static final int CONFIDENCE_WEIGHT_MEDIUM = 10;
    private static final int CONFIDENCE_WEIGHT_LOW = 5;
    private static final int RECENT_DIMENSION_WEIGHT = 10;
    private static final int DEFAULT_DIMENSION_WEIGHT = 5;
    private static final double PRIORITY_HIGH_THRESHOLD = 55.0;
    private static final double PRIORITY_MEDIUM_THRESHOLD = 30.0;

    /** Deterministic action for each weakness dimension. */
    private static final Map<Dimension, RecommendationCode> WEAKNESS_ACTION = Map.of(
            Dimension.EASY_DIFFICULTY, RecommendationCode.REINFORCE_BASICS,
            Dimension.MEDIUM_DIFFICULTY, RecommendationCode.PRACTICE_MEDIUM_QUESTIONS,
            Dimension.HARD_DIFFICULTY, RecommendationCode.PRACTICE_HARD_QUESTIONS,
            Dimension.EXAM_PERFORMANCE, RecommendationCode.REVIEW_RECENT_EXAMS,
            Dimension.PRACTICE_PERFORMANCE, RecommendationCode.INCREASE_PRACTICE,
            Dimension.RECENT_PERFORMANCE, RecommendationCode.REVIEW_RECENT_EXAMS,
            Dimension.CONSISTENCY, RecommendationCode.COMPLETE_REGULAR_PRACTICE);

    private final LearningProfileService learningProfileService;

    public LearningIntelligenceService(LearningProfileService learningProfileService) {
        this.learningProfileService = learningProfileService;
    }

    public LearningIntelligence build(String studentId) {
        return build(learningProfileService.build(studentId));
    }

    /** Reuses an already-built profile so callers can avoid recomputing the learning profile. */
    public LearningIntelligence build(LearningProfile profile) {
        List<Strength> strengths = new ArrayList<>();
        List<Weakness> weaknesses = new ArrayList<>();

        evaluateDifficulty(Dimension.EASY_DIFFICULTY, profile.difficulty().exam().easy(), "HIGH_EASY_ACCURACY", "LOW_EASY_ACCURACY", strengths, weaknesses);
        evaluateDifficulty(Dimension.MEDIUM_DIFFICULTY, profile.difficulty().exam().medium(), "HIGH_MEDIUM_ACCURACY", "LOW_MEDIUM_ACCURACY", strengths, weaknesses);
        evaluateDifficulty(Dimension.HARD_DIFFICULTY, profile.difficulty().exam().hard(), "HIGH_HARD_ACCURACY", "LOW_HARD_ACCURACY", strengths, weaknesses);
        evaluateExamPerformance(profile, strengths, weaknesses);
        evaluatePractice(profile, strengths, weaknesses);
        evaluateRecent(profile, strengths, weaknesses);
        evaluateConsistency(profile, strengths, weaknesses);

        List<Strength> orderedStrengths = strengths.stream()
                .sorted(Comparator.comparingInt((Strength s) -> -s.observations()).thenComparing(s -> s.dimension().name()))
                .toList();
        List<Weakness> orderedWeaknesses = weaknesses.stream()
                .sorted(Comparator.comparing(s -> s.dimension().name()))
                .toList();

        List<Priority> priorities = priorities(orderedWeaknesses);
        List<Recommendation> recommendations = recommendations(orderedWeaknesses);

        int observationCount = observationCount(profile);
        int strengthCount = orderedStrengths.size();
        int weaknessCount = orderedWeaknesses.size();
        DataQuality dataQuality = new DataQuality(
                profile.examPerformance().averageAccuracy(),
                observationCount,
                strengthCount,
                weaknessCount,
                observationCount >= MIN_OBSERVATIONS);

        return new LearningIntelligence(orderedStrengths, orderedWeaknesses, priorities, recommendations, dataQuality);
    }

    private void evaluateDifficulty(Dimension dimension, DifficultyLevel level, String strengthSignal,
                                    String weaknessSignal, List<Strength> strengths, List<Weakness> weaknesses) {
        if (level == null || level.attempted() < MIN_OBSERVATIONS || level.accuracy() == null) {
            return;
        }
        double accuracy = level.accuracy();
        Confidence confidence = confidence(level.attempted());
        if (accuracy >= STRENGTH_ACCURACY_PERCENT) {
            strengths.add(new Strength(dimension, round(accuracy), level.attempted(), confidence, strengthSignal));
        } else if (accuracy <= WEAKNESS_ACCURACY_PERCENT) {
            weaknesses.add(new Weakness(dimension, round(accuracy), level.attempted(),
                    severityFor(accuracy), confidence, weaknessSignal));
        }
    }

    private void evaluateExamPerformance(LearningProfile profile, List<Strength> strengths, List<Weakness> weaknesses) {
        Double accuracy = profile.examPerformance().averageAccuracy();
        int observations = examGradedAnswers(profile);
        if (accuracy == null || observations < MIN_OBSERVATIONS) {
            return;
        }
        if (accuracy >= STRENGTH_ACCURACY_PERCENT) {
            strengths.add(new Strength(Dimension.EXAM_PERFORMANCE, round(accuracy), observations,
                    confidence(observations), "HIGH_EXAM_ACCURACY"));
        } else if (accuracy <= WEAKNESS_ACCURACY_PERCENT) {
            weaknesses.add(new Weakness(Dimension.EXAM_PERFORMANCE, round(accuracy), observations,
                    severityFor(accuracy), confidence(observations), "LOW_EXAM_ACCURACY"));
        }
    }

    private void evaluatePractice(LearningProfile profile, List<Strength> strengths, List<Weakness> weaknesses) {
        Double accuracy = profile.practice().accuracy();
        int observations = profile.practice().questionsAnswered();
        if (accuracy == null || observations < MIN_OBSERVATIONS) {
            return;
        }
        if (accuracy >= STRENGTH_ACCURACY_PERCENT) {
            strengths.add(new Strength(Dimension.PRACTICE_PERFORMANCE, round(accuracy), observations,
                    confidence(observations), "HIGH_PRACTICE_ACCURACY"));
        } else if (accuracy <= WEAKNESS_ACCURACY_PERCENT) {
            weaknesses.add(new Weakness(Dimension.PRACTICE_PERFORMANCE, round(accuracy), observations,
                    severityFor(accuracy), confidence(observations), "LOW_PRACTICE_ACCURACY"));
        }
    }

    private void evaluateRecent(LearningProfile profile, List<Strength> strengths, List<Weakness> weaknesses) {
        String direction = profile.trend().direction();
        Double delta = profile.trend().delta();
        if ("DECLINING".equals(direction)) {
            Double accuracy = profile.examPerformance().recentAccuracy() != null
                    ? profile.examPerformance().recentAccuracy()
                    : profile.examPerformance().averageAccuracy();
            if (accuracy == null) {
                return;
            }
            int observations = profile.overall().completedExams();
            double decline = delta == null ? 0.0 : Math.abs(delta);
            Severity severity = decline >= SEVERE_DECLINE_DELTA_PERCENT ? Severity.HIGH : Severity.MEDIUM;
            weaknesses.add(new Weakness(Dimension.RECENT_PERFORMANCE, round(accuracy), observations,
                    severity, confidenceForExams(observations), "RECENT_PERFORMANCE_DECLINING"));
        } else if ("IMPROVING".equals(direction)) {
            Double accuracy = profile.examPerformance().recentAccuracy() != null
                    ? profile.examPerformance().recentAccuracy()
                    : profile.examPerformance().averageAccuracy();
            int observations = profile.overall().completedExams();
            if (accuracy != null && accuracy >= STRENGTH_ACCURACY_PERCENT && observations >= MIN_OBSERVATIONS) {
                strengths.add(new Strength(Dimension.RECENT_PERFORMANCE, round(accuracy), observations,
                        confidenceForExams(observations), "RECENT_PERFORMANCE_IMPROVING"));
            }
        }
    }

    private void evaluateConsistency(LearningProfile profile, List<Strength> strengths, List<Weakness> weaknesses) {
        int observations = profile.overall().completedExams();
        if (observations < MIN_EXAMS_FOR_CONSISTENCY) {
            return;
        }
        String consistency = profile.learningSignals().consistency();
        if ("VARIABLE".equals(consistency)) {
            weaknesses.add(new Weakness(Dimension.CONSISTENCY, 0.0, observations,
                    Severity.MEDIUM, confidenceForExams(observations), "CONSISTENCY_VARIABLE"));
        } else if ("STABLE".equals(consistency)) {
            strengths.add(new Strength(Dimension.CONSISTENCY, 100.0, observations,
                    confidenceForExams(observations), "CONSISTENCY_STABLE"));
        }
    }

    private List<Priority> priorities(List<Weakness> weaknesses) {
        return weaknesses.stream()
                .map(weakness -> new PriorityCandidate(weakness, priorityScore(weakness)))
                .sorted(Comparator.comparingInt((PriorityCandidate candidate) -> -candidate.score())
                        .thenComparing(candidate -> candidate.weakness().dimension().name()))
                .map(candidate -> {
                    Weakness weakness = candidate.weakness();
                    return new Priority(
                            weakness.dimension(),
                            priorityLevel(candidate.score()),
                            priorityReason(weakness),
                            WEAKNESS_ACTION.get(weakness.dimension()));
                })
                .toList();
    }

    private List<Recommendation> recommendations(List<Weakness> weaknesses) {
        Set<RecommendationCode> codes = new LinkedHashSet<>();
        for (Weakness weakness : weaknesses) {
            codes.add(WEAKNESS_ACTION.get(weakness.dimension()));
        }
        return codes.stream().map(Recommendation::new).toList();
    }

    private int priorityScore(Weakness weakness) {
        return severityWeight(weakness.severity())
                + gapPoints(weakness)
                + confidenceWeight(weakness.confidence())
                + dimensionWeight(weakness.dimension());
    }

    private int gapPoints(Weakness weakness) {
        int gap = (int) Math.floor(Math.max(0.0, TARGET_ACCURACY_PERCENT - weakness.accuracy()));
        return Math.min(gap, GAP_CAP);
    }

    private int severityWeight(Severity severity) {
        return switch (severity) {
            case HIGH -> SEVERITY_WEIGHT_HIGH;
            case MEDIUM -> SEVERITY_WEIGHT_MEDIUM;
            case LOW -> SEVERITY_WEIGHT_LOW;
        };
    }

    private int confidenceWeight(Confidence confidence) {
        return switch (confidence) {
            case HIGH_CONFIDENCE -> CONFIDENCE_WEIGHT_HIGH;
            case MEDIUM_CONFIDENCE -> CONFIDENCE_WEIGHT_MEDIUM;
            case LOW_CONFIDENCE -> CONFIDENCE_WEIGHT_LOW;
            case INSUFFICIENT_DATA -> CONFIDENCE_WEIGHT_LOW;
        };
    }

    private int dimensionWeight(Dimension dimension) {
        return dimension == Dimension.RECENT_PERFORMANCE ? RECENT_DIMENSION_WEIGHT : DEFAULT_DIMENSION_WEIGHT;
    }

    private PriorityLevel priorityLevel(int score) {
        if (score >= PRIORITY_HIGH_THRESHOLD) {
            return PriorityLevel.HIGH;
        }
        if (score >= PRIORITY_MEDIUM_THRESHOLD) {
            return PriorityLevel.MEDIUM;
        }
        return PriorityLevel.LOW;
    }

    private String priorityReason(Weakness weakness) {
        int gap = (int) Math.floor(Math.max(0.0, TARGET_ACCURACY_PERCENT - weakness.accuracy()));
        return String.format("%s accuracy %.1f%% over %d observations; performance gap of %d points below the %.0f%% target",
                weakness.dimension(), weakness.accuracy(), weakness.observations(), gap, TARGET_ACCURACY_PERCENT);
    }

    private int observationCount(LearningProfile profile) {
        return examGradedAnswers(profile) + profile.practice().questionsAnswered();
    }

    private int examGradedAnswers(LearningProfile profile) {
        return profile.difficulty().exam().easy().attempted()
                + profile.difficulty().exam().medium().attempted()
                + profile.difficulty().exam().hard().attempted();
    }

    private Severity severityFor(double accuracy) {
        if (accuracy < SEVERE_WEAKNESS_PERCENT) {
            return Severity.HIGH;
        }
        if (accuracy < MODERATE_WEAKNESS_PERCENT) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }

    private Confidence confidence(int observations) {
        if (observations < MIN_OBSERVATIONS) {
            return Confidence.INSUFFICIENT_DATA;
        }
        if (observations < CONFIDENCE_MEDIUM_MIN) {
            return Confidence.LOW_CONFIDENCE;
        }
        if (observations < CONFIDENCE_HIGH_MIN) {
            return Confidence.MEDIUM_CONFIDENCE;
        }
        return Confidence.HIGH_CONFIDENCE;
    }

    private Confidence confidenceForExams(int exams) {
        if (exams < CONFIDENCE_MEDIUM_MIN_EXAMS) {
            return Confidence.LOW_CONFIDENCE;
        }
        if (exams < CONFIDENCE_HIGH_MIN_EXAMS) {
            return Confidence.MEDIUM_CONFIDENCE;
        }
        return Confidence.HIGH_CONFIDENCE;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public void requireStudent(User actor) {
        learningProfileService.requireStudent(actor);
    }

    private record PriorityCandidate(Weakness weakness, int score) {
    }
}
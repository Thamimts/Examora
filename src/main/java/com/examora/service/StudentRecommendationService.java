package com.examora.service;

import com.examora.dto.LearningIntelligenceDtos.Confidence;
import com.examora.dto.LearningIntelligenceDtos.Dimension;
import com.examora.dto.LearningIntelligenceDtos.LearningIntelligence;
import com.examora.dto.LearningIntelligenceDtos.PriorityLevel;
import com.examora.dto.LearningIntelligenceDtos.Severity;
import com.examora.dto.LearningIntelligenceDtos.Strength;
import com.examora.dto.LearningIntelligenceDtos.Weakness;
import com.examora.dto.LearningProfileDtos.DifficultyLevel;
import com.examora.dto.LearningProfileDtos.LearningProfile;
import com.examora.dto.StudentProgressDtos.DifficultyProgression;
import com.examora.dto.StudentProgressDtos.StudentProgress;
import com.examora.dto.StudentRecommendationDtos.Action;
import com.examora.dto.StudentRecommendationDtos.Recommendation;
import com.examora.dto.StudentRecommendationDtos.RecommendationCode;
import com.examora.dto.StudentRecommendationDtos.RecommendationSummary;
import com.examora.dto.StudentRecommendationDtos.StudentRecommendations;
import com.examora.dto.StudentRecommendationDtos.SupportingMetrics;
import com.examora.model.User;
import com.examora.repository.AnalyticsRepository;
import com.examora.repository.AnalyticsRepository.PracticeCompletionRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Deterministic Personalized Practice Recommendation Engine (P5D.4).
 *
 * <p>Answers "what should this student practice next?" by ranking risks and opportunities that are
 * strictly derivable from the P5D.1 learning profile, the P5D.2 strength/weakness intelligence and
 * the P5D.3 progress intelligence. No AI, no fabricated topics, no question selection, no automatic
 * session creation. Every computation is an explicit pure function of persisted rows.
 */
@Service
public class StudentRecommendationService {

    // ----- thresholds (mirror P5D.2 / P5D.3) -----
    private static final double STRENGTH_ACCURACY_PERCENT = 80.0;
    private static final double WEAKNESS_ACCURACY_PERCENT = 60.0;
    private static final double SEVERE_DECLINE_DELTA_PERCENT = 10.0;
    private static final int MIN_OBSERVATIONS = 5;
    private static final int CONFIDENCE_MEDIUM_MIN = 10;
    private static final int CONFIDENCE_HIGH_MIN = 20;

    // ----- priority hierarchy tiers (lower = more urgent) -----
    private static final int TIER_SEVERE_WEAKNESS = 1;
    private static final int TIER_HIGH_PRIORITY_WEAKNESS = 2;
    private static final int TIER_RECENT_DECLINE = 3;
    private static final int TIER_PERSISTENT_WEAKNESS = 4;
    private static final int TIER_IMPROVEMENT_OPPORTUNITY = 5;
    private static final int TIER_MAINTENANCE = 6;
    private static final int TIER_BASELINE = 7;

    // ----- deterministic, bounded target question counts -----
    private static final int TARGET_HIGH = 10;
    private static final int TARGET_MEDIUM = 8;
    private static final int TARGET_LOW = 5;

    /** After completing equivalent practice within this window the same action is not repeated. */
    private static final Duration REPETITION_COOLDOWN = Duration.ofHours(72);

    // ----- difficulty mapping (1-5 model -> buckets) -----
    private static final int EASY_MIN = 1;
    private static final int EASY_MAX = 2;
    private static final int MEDIUM_VALUE = 3;
    private static final int HARD_MIN = 4;

    private static final Map<Dimension, RecommendationCode> DIFFICULTY_CODE = Map.of(
            Dimension.EASY_DIFFICULTY, RecommendationCode.PRACTICE_EASY_QUESTIONS,
            Dimension.MEDIUM_DIFFICULTY, RecommendationCode.PRACTICE_MEDIUM_QUESTIONS,
            Dimension.HARD_DIFFICULTY, RecommendationCode.PRACTICE_HARD_QUESTIONS);

    private final LearningProfileService learningProfileService;
    private final LearningIntelligenceService learningIntelligenceService;
    private final StudentProgressService studentProgressService;
    private final AnalyticsRepository analyticsRepository;

    public StudentRecommendationService(LearningProfileService learningProfileService,
                                        LearningIntelligenceService learningIntelligenceService,
                                        StudentProgressService studentProgressService,
                                        AnalyticsRepository analyticsRepository) {
        this.learningProfileService = learningProfileService;
        this.learningIntelligenceService = learningIntelligenceService;
        this.studentProgressService = studentProgressService;
        this.analyticsRepository = analyticsRepository;
    }

    public StudentRecommendations build(String studentId) {
        LearningProfile profile = learningProfileService.build(studentId);
        LearningIntelligence intelligence = learningIntelligenceService.build(profile);
        StudentProgress progress = studentProgressService.build(studentId, profile);
        Map<String, Instant> lastPracticeByBucket = lastPracticeByBucket(studentId);

        Map<Dimension, PriorityLevel> p2Priority = p2Priority(intelligence);
        Map<Dimension, Weakness> weaknesses = weaknessesByDimension(intelligence);
        List<Candidate> candidates = new ArrayList<>();

        for (Dimension dimension : List.of(Dimension.EASY_DIFFICULTY, Dimension.MEDIUM_DIFFICULTY,
                Dimension.HARD_DIFFICULTY)) {
            Weakness weakness = weaknesses.get(dimension);
            if (weakness != null) {
                String difficulty = difficultyName(dimension);
                TierPriority tier = tierForWeakness(weakness, p2Priority);
                DifficultyProgression progression = progression(progress, bucketName(dimension));
                candidates.add(new Candidate(
                        DIFFICULTY_CODE.get(dimension),
                        tier.tier(),
                        tier.priority(),
                        Action.PRACTICE,
                        dimension,
                        difficulty,
                        weakness.signal(),
                        weakness.confidence(),
                        metrics(weakness.accuracy(), weakness.observations(),
                                accuracy(progression, true), accuracy(progression, false),
                                recentlyPracticed(lastPracticeByBucket, bucketName(dimension)))));
            }
        }

        Candidate review = reviewCandidate(intelligence, weaknesses, progress, p2Priority);
        if (review != null) {
            candidates.add(review);
        }

        Weakness practiceWeakness = weaknesses.get(Dimension.PRACTICE_PERFORMANCE);
        if (practiceWeakness != null) {
            TierPriority tier = tierForWeakness(practiceWeakness, p2Priority);
            candidates.add(new Candidate(
                    RecommendationCode.INCREASE_PRACTICE,
                    tier.tier(),
                    tier.priority(),
                    Action.PRACTICE,
                    Dimension.PRACTICE_PERFORMANCE,
                    null,
                    "LOW_PRACTICE_ACCURACY",
                    practiceWeakness.confidence(),
                    metrics(practiceWeakness.accuracy(), practiceWeakness.observations(),
                            null, null, false)));
        }

        addImprovementOpportunities(candidates, profile, progress, lastPracticeByBucket);
        addMaintenance(candidates, intelligence, progress, lastPracticeByBucket);

        List<Candidate> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::tier)
                        .thenComparing(candidate -> priorityWeight(candidate.priority()), Comparator.reverseOrder())
                        .thenComparing(candidate -> candidate.code().name())
                        .thenComparing(candidate -> candidate.dimension() == null ? ""
                                : candidate.dimension().name())
                        .thenComparing(candidate -> candidate.reasonCode() == null ? "" : candidate.reasonCode()))
                .toList();

        List<Recommendation> recommendations = ordered.stream()
                .map(candidate -> new Recommendation(
                        candidate.code(),
                        candidate.priority(),
                        candidate.action(),
                        candidate.dimension(),
                        candidate.difficulty(),
                        candidate.reasonCode(),
                        candidate.confidence(),
                        targetCount(candidate.priority()),
                        candidate.metrics()))
                .toList();

        if (recommendations.isEmpty()) {
            recommendations = List.of(new Recommendation(
                    RecommendationCode.BUILD_BASELINE,
                    PriorityLevel.LOW,
                    Action.BASELINE,
                    null,
                    null,
                    "INSUFFICIENT_DATA",
                    Confidence.INSUFFICIENT_DATA,
                    TARGET_LOW,
                    new SupportingMetrics(null, intelligence.dataQuality().observationCount(),
                            null, null, false)));
        }

        String primary = recommendations.isEmpty() ? null : recommendations.get(0).code().name();
        RecommendationSummary summary = new RecommendationSummary(
                primary, recommendations.size(), intelligence.dataQuality().sufficientData());

        return new StudentRecommendations(recommendations, summary, Instant.now().toString());
    }

    // ----- review recommendation (recent decline OR weak overall exam performance) -----

    private Candidate reviewCandidate(LearningIntelligence intelligence, Map<Dimension, Weakness> weaknesses,
                                      StudentProgress progress, Map<Dimension, PriorityLevel> p2Priority) {
        boolean declining = "DECLINING".equals(progress.overall().direction());

        if (declining) {
            Double delta = progress.overall().delta() == null ? 0.0 : Math.abs(progress.overall().delta());
            PriorityLevel priority = delta >= SEVERE_DECLINE_DELTA_PERCENT
                    ? PriorityLevel.HIGH : PriorityLevel.MEDIUM;
            Weakness recent = weaknesses.get(Dimension.RECENT_PERFORMANCE);
            Confidence confidence = recent != null
                    ? recent.confidence()
                    : confidenceForExams(progress.dataQuality().completedExamCount());
            Integer observations = progress.dataQuality().completedExamCount();
            Double accuracy = recent != null ? recent.accuracy()
                    : progress.overall().recentAverage();
            return new Candidate(RecommendationCode.REVIEW_RECENT_EXAMS,
                    TIER_RECENT_DECLINE,
                    priority,
                    Action.REVIEW,
                    Dimension.RECENT_PERFORMANCE,
                    null,
                    "RECENT_DECLINE",
                    confidence,
                    metrics(accuracy, observations,
                            progress.overall().recentAverage(), progress.overall().previousAverage(), false));
        }

        Weakness exam = weaknesses.get(Dimension.EXAM_PERFORMANCE);
        if (exam != null) {
            TierPriority tier = tierForWeakness(exam, p2Priority);
            return new Candidate(RecommendationCode.REVIEW_RECENT_EXAMS,
                    tier.tier(),
                    tier.priority(),
                    Action.REVIEW,
                    Dimension.EXAM_PERFORMANCE,
                    null,
                    "LOW_EXAM_ACCURACY",
                    exam.confidence(),
                    metrics(exam.accuracy(), exam.observations(),
                            progress.overall().recentAverage(), progress.overall().previousAverage(), false));
        }

        return null;
    }

    // ----- improvement opportunities (60 < accuracy < 80, enough observations) -----

    private void addImprovementOpportunities(List<Candidate> candidates, LearningProfile profile,
                                             StudentProgress progress, Map<String, Instant> lastPractice) {
        addImprovementOpportunity(candidates, "easy", Dimension.EASY_DIFFICULTY, RecommendationCode.PRACTICE_EASY_QUESTIONS,
                profile.difficulty().exam().easy(), progress, lastPractice);
        addImprovementOpportunity(candidates, "medium", Dimension.MEDIUM_DIFFICULTY, RecommendationCode.PRACTICE_MEDIUM_QUESTIONS,
                profile.difficulty().exam().medium(), progress, lastPractice);
        addImprovementOpportunity(candidates, "hard", Dimension.HARD_DIFFICULTY, RecommendationCode.PRACTICE_HARD_QUESTIONS,
                profile.difficulty().exam().hard(), progress, lastPractice);
    }

    private void addImprovementOpportunity(List<Candidate> candidates, String bucket, Dimension dimension,
                                           RecommendationCode code, DifficultyLevel level, StudentProgress progress,
                                           Map<String, Instant> lastPractice) {
        if (level == null || level.attempted() < MIN_OBSERVATIONS || level.accuracy() == null) {
            return;
        }
        double accuracy = level.accuracy();
        if (accuracy <= WEAKNESS_ACCURACY_PERCENT || accuracy >= STRENGTH_ACCURACY_PERCENT) {
            return;
        }
        if (recentlyPracticed(lastPractice, bucket)) {
            return;
        }
        DifficultyProgression progression = progression(progress, bucket);
        candidates.add(new Candidate(code, TIER_IMPROVEMENT_OPPORTUNITY, PriorityLevel.LOW,
                Action.PRACTICE, dimension, difficultyName(dimension), "IMPROVEMENT_OPPORTUNITY",
                bandConfidence(level.attempted()),
                metrics(round(accuracy), level.attempted(),
                        accuracy(progression, true), accuracy(progression, false), false)));
    }

    // ----- maintenance (strengths) -----

    private void addMaintenance(List<Candidate> candidates, LearningIntelligence intelligence,
                                StudentProgress progress, Map<String, Instant> lastPractice) {
        for (Strength strength : intelligence.strengths()) {
            String difficulty = difficultyName(strength.dimension());
            String bucket = bucketName(strength.dimension());
            boolean suppress = bucket != null && recentlyPracticed(lastPractice, bucket);
            if (suppress) {
                continue;
            }
            candidates.add(new Candidate(RecommendationCode.MAINTAIN_STRENGTH, TIER_MAINTENANCE,
                    PriorityLevel.LOW, Action.MAINTENANCE, strength.dimension(), difficulty,
                    "STRENGTH_MAINTAINED", strength.confidence(),
                    metrics(strength.accuracy(), strength.observations(),
                            accuracy(progression(progress, bucket), true),
                            accuracy(progression(progress, bucket), false), false)));
        }
    }

    // ----- supportive metrics + helpers -----

    private SupportingMetrics metrics(Double accuracy, Integer observations,
                                      Double recentAccuracy, Double previousAccuracy, boolean recentlyPracticed) {
        return new SupportingMetrics(accuracy, observations, recentAccuracy, previousAccuracy, recentlyPracticed);
    }

    private Double accuracy(DifficultyProgression progression, boolean recent) {
        if (progression == null) {
            return null;
        }
        return recent ? progression.recentAccuracy() : progression.previousAccuracy();
    }

    private DifficultyProgression progression(StudentProgress progress, String bucket) {
        if (bucket == null) {
            return null;
        }
        return switch (bucket) {
            case "easy" -> progress.difficultyProgress().easy();
            case "medium" -> progress.difficultyProgress().medium();
            default -> progress.difficultyProgress().hard();
        };
    }

    private Map<Dimension, PriorityLevel> p2Priority(LearningIntelligence intelligence) {
        Map<Dimension, PriorityLevel> map = new HashMap<>();
        for (var priority : intelligence.priorities()) {
            map.put(priority.dimension(), priority.priorityLevel());
        }
        return map;
    }

    private Map<Dimension, Weakness> weaknessesByDimension(LearningIntelligence intelligence) {
        Map<Dimension, Weakness> map = new LinkedHashMap<>();
        for (Weakness weakness : intelligence.weaknesses()) {
            map.putIfAbsent(weakness.dimension(), weakness);
        }
        return map;
    }

    /** Tier/priority for a generic weakness, consuming the P5D.2 priority model where possible. */
    private TierPriority tierForWeakness(Weakness weakness, Map<Dimension, PriorityLevel> p2Priority) {
        if (weakness.severity() == Severity.HIGH) {
            return new TierPriority(TIER_SEVERE_WEAKNESS, PriorityLevel.HIGH);
        }
        if (p2Priority.getOrDefault(weakness.dimension(), PriorityLevel.LOW) == PriorityLevel.HIGH) {
            return new TierPriority(TIER_HIGH_PRIORITY_WEAKNESS, PriorityLevel.HIGH);
        }
        return new TierPriority(TIER_PERSISTENT_WEAKNESS, PriorityLevel.MEDIUM);
    }

    private boolean recentlyPracticed(Map<String, Instant> lastByBucket, String bucket) {
        if (bucket == null) {
            return false;
        }
        Instant last = lastByBucket.get(bucket);
        if (last == null) {
            return false;
        }
        return !last.isBefore(Instant.now().minus(REPETITION_COOLDOWN));
    }

    private Map<String, Instant> lastPracticeByBucket(String studentId) {
        Map<String, Instant> byBucket = new HashMap<>();
        for (PracticeCompletionRow row : analyticsRepository.findLastPracticeCompletionByDifficulty(studentId)) {
            String bucket = bucketFor(row.difficulty());
            Instant existing = byBucket.get(bucket);
            if (existing == null || row.lastCompletedAt() != null
                    && (row.lastCompletedAt().isAfter(existing))) {
                byBucket.put(bucket, row.lastCompletedAt());
            }
        }
        return byBucket;
    }

    private String difficultyName(Dimension dimension) {
        return switch (dimension) {
            case EASY_DIFFICULTY -> "EASY";
            case MEDIUM_DIFFICULTY -> "MEDIUM";
            case HARD_DIFFICULTY -> "HARD";
            default -> null;
        };
    }

    private String bucketName(Dimension dimension) {
        return switch (dimension) {
            case EASY_DIFFICULTY -> "easy";
            case MEDIUM_DIFFICULTY -> "medium";
            case HARD_DIFFICULTY -> "hard";
            default -> null;
        };
    }

    private String bucketFor(int difficulty) {
        if (difficulty >= EASY_MIN && difficulty <= EASY_MAX) {
            return "easy";
        }
        if (difficulty == MEDIUM_VALUE) {
            return "medium";
        }
        return "hard";
    }

    private int targetCount(PriorityLevel priority) {
        return switch (priority) {
            case HIGH -> TARGET_HIGH;
            case MEDIUM -> TARGET_MEDIUM;
            case LOW -> TARGET_LOW;
        };
    }

    private int priorityWeight(PriorityLevel priority) {
        return switch (priority) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    private Confidence bandConfidence(int observations) {
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
        if (exams < CONFIDENCE_MEDIUM_MIN) {
            return Confidence.LOW_CONFIDENCE;
        }
        if (exams < CONFIDENCE_HIGH_MIN) {
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

    private record Candidate(RecommendationCode code, int tier, PriorityLevel priority, Action action,
                             Dimension dimension, String difficulty, String reasonCode,
                             Confidence confidence, SupportingMetrics metrics) {
    }

    private record TierPriority(int tier, PriorityLevel priority) {
    }
}
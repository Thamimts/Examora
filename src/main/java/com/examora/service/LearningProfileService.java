package com.examora.service;

import com.examora.dto.LearningProfileDtos.AiPractice;
import com.examora.dto.LearningProfileDtos.Difficulty;
import com.examora.dto.LearningProfileDtos.DifficultyBucket;
import com.examora.dto.LearningProfileDtos.DifficultyLevel;
import com.examora.dto.LearningProfileDtos.ExamPerformance;
import com.examora.dto.LearningProfileDtos.LearningProfile;
import com.examora.dto.LearningProfileDtos.LearningSignals;
import com.examora.dto.LearningProfileDtos.Overall;
import com.examora.dto.LearningProfileDtos.Practice;
import com.examora.dto.LearningProfileDtos.Trend;
import com.examora.model.Result;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.AnalyticsRepository;
import com.examora.repository.ResultRepository;
import com.examora.exception.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Server-authoritative Student Learning Profile.
 *
 * <p>Every metric is computed on read from persisted records. Difficulty values are the persisted
 * 1-5 model on {@code questions.difficulty} (exam answers) and {@code practice_answers.difficulty}
 * (adaptive practice). The AI practice system stores EASY/MEDIUM/HARD strings and is reported in a
 * separate {@code aiPractice} block because its semantics differ.
 */
@Service
public class LearningProfileService {

    /** Difficulty mapping (1-5 model) -> buckets. */
    private static final int EASY_MIN = 1;
    private static final int EASY_MAX = 2;
    private static final int MEDIUM_VALUE = 3;
    private static final int HARD_MIN = 4;

    /** Trend needs at least RECENT_WINDOW + PREVIOUS_WINDOW completed exams. */
    private static final int RECENT_WINDOW = 2;
    private static final int MIN_COMPLETED_EXAMS_FOR_TREND = RECENT_WINDOW * 2;
    /** |delta| percentage points below this is considered noise (STABLE). */
    private static final double MEANINGFUL_DELTA = 2.0;

    /** Minimum graded answers in a difficulty bucket to emit an accuracy signal. */
    private static final int MIN_SIGNAL_SAMPLE = 5;
    /** Accuracy at or above this is a strength. */
    private static final double HIGH_ACCURACY_PERCENT = 80.0;
    /** Accuracy at or below this is a weakness. */
    private static final double LOW_ACCURACY_PERCENT = 60.0;
    /** Minimum practice answers to emit a practice performance signal. */
    private static final int MIN_PRACTICE_SAMPLE = 5;

    /** Recent practice accuracy window (most recent answers). */
    private static final int PRACTICE_RECENT_LIMIT = 20;

    /** Minimum completed exams to classify consistency. */
    private static final int CONSISTENCY_MIN_EXAMS = 3;
    /** Standard deviation (percentage points) at or below this means STABLE. */
    private static final double CONSISTENCY_STABLE_STDDEV = 10.0;

    private final ResultRepository resultRepository;
    private final AnalyticsRepository analyticsRepository;

    public LearningProfileService(ResultRepository resultRepository, AnalyticsRepository analyticsRepository) {
        this.resultRepository = resultRepository;
        this.analyticsRepository = analyticsRepository;
    }

    public LearningProfile build(String studentId) {
        List<Result> results = chronologicalResults(studentId);

        int submittedAttempts = analyticsRepository.countSubmittedAttempts(studentId);

        Overall overall = overall(results, submittedAttempts);

        Trend trend = trend(results);

        List<String> recentExamIds = windowExamIds(results, RECENT_WINDOW);
        Double recentAccuracy = accuracy(
                analyticsRepository.findGradedAccuracyForExams(studentId, recentExamIds));

        Double improvementDelta = improvementDelta(results);

        ExamPerformance examPerformance = new ExamPerformance(
                accuracy(analyticsRepository.findGradedAccuracy(studentId)),
                recentAccuracy,
                improvementDelta);

        Practice practice = practice(studentId);
        AiPractice aiPractice = aiPractice(studentId);

        Difficulty difficulty = difficulty(studentId);

        LearningSignals signals = learningSignals(results, practice, difficulty, trend);

        return new LearningProfile(overall, examPerformance, practice, aiPractice, difficulty, trend, signals);
    }

    private Overall overall(List<Result> results, int submittedAttempts) {
        return new Overall(
                averagePercentage(results),
                highestPercentage(results),
                lowestPercentage(results),
                results.size(),
                submittedAttempts);
    }

    private Practice practice(String studentId) {
        AnalyticsRepository.PracticeAnswerRow answers = analyticsRepository.findPracticeAnswerAggregates(studentId, null);
        Double accuracy = percentage(answers.correct(), answers.questions());
        AnalyticsRepository.PracticeAnswerRow recent = analyticsRepository.findRecentPracticeAccuracy(
                studentId, PRACTICE_RECENT_LIMIT);
        Double recentAccuracy = percentage(recent.correct(), recent.questions());
        return new Practice(
                analyticsRepository.countCompletedPracticeSessions(studentId),
                answers.questions(),
                answers.correct(),
                accuracy,
                recentAccuracy);
    }

    private AiPractice aiPractice(String studentId) {
        AnalyticsRepository.PracticeAnswerRow answers = analyticsRepository.findAiPracticeAnswerAggregates(studentId);
        return new AiPractice(
                analyticsRepository.countCompletedAiPracticeSessions(studentId),
                answers.questions(),
                answers.correct(),
                percentage(answers.correct(), answers.questions()));
    }

    private Difficulty difficulty(String studentId) {
        DifficultyBucket exam = toBucket(analyticsRepository.findDifficultyPerformance(studentId).stream()
                .map(row -> new Counted(row.difficulty(), row.graded(), row.correct()))
                .toList());
        DifficultyBucket practice = toBucket(analyticsRepository.findPracticeDifficulty(studentId, null).stream()
                .map(row -> new Counted(row.difficulty(), row.questions(), row.correct()))
                .toList());
        return new Difficulty(exam, practice);
    }

    private DifficultyBucket toBucket(List<Counted> rows) {
        Map<String, int[]> byBucket = new HashMap<>();
        byBucket.put("easy", new int[]{0, 0});
        byBucket.put("medium", new int[]{0, 0});
        byBucket.put("hard", new int[]{0, 0});
        for (Counted row : rows) {
            int[] current = byBucket.get(bucketFor(row.difficulty()));
            current[0] += row.attempted();
            current[1] += row.correct();
        }
        return new DifficultyBucket(
                toLevel(byBucket.get("easy")),
                toLevel(byBucket.get("medium")),
                toLevel(byBucket.get("hard")));
    }

    private DifficultyLevel toLevel(int[] counts) {
        return new DifficultyLevel(counts[0], counts[1], percentage(counts[1], counts[0]));
    }

    /** Deterministic mapping of the persisted 1-5 difficulty into EASY / MEDIUM / HARD. */
    private String bucketFor(int difficulty) {
        if (difficulty <= EASY_MAX && difficulty >= EASY_MIN) {
            return "easy";
        }
        if (difficulty == MEDIUM_VALUE) {
            return "medium";
        }
        return "hard";
    }

    private LearningSignals learningSignals(List<Result> results, Practice practice,
                                            Difficulty difficulty, Trend trend) {
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();

        DifficultyLevel examEasy = difficulty.exam().easy();
        DifficultyLevel examMedium = difficulty.exam().medium();
        DifficultyLevel examHard = difficulty.exam().hard();

        if (examEasy.attempted() >= MIN_SIGNAL_SAMPLE
                && examEasy.accuracy() != null && examEasy.accuracy() >= HIGH_ACCURACY_PERCENT) {
            strengths.add("HIGH_EASY_ACCURACY");
        }
        if (examMedium.attempted() >= MIN_SIGNAL_SAMPLE
                && examMedium.accuracy() != null && examMedium.accuracy() <= LOW_ACCURACY_PERCENT) {
            weaknesses.add("LOW_MEDIUM_ACCURACY");
        }
        if (examHard.attempted() >= MIN_SIGNAL_SAMPLE
                && examHard.accuracy() != null && examHard.accuracy() <= LOW_ACCURACY_PERCENT) {
            weaknesses.add("LOW_HARD_ACCURACY");
        }

        if (practice.questionsAnswered() >= MIN_PRACTICE_SAMPLE) {
            if (practice.accuracy() != null && practice.accuracy() >= HIGH_ACCURACY_PERCENT) {
                strengths.add("STRONG_PRACTICE_PERFORMANCE");
            }
            if (practice.accuracy() != null && practice.accuracy() <= LOW_ACCURACY_PERCENT) {
                weaknesses.add("WEAK_PRACTICE_PERFORMANCE");
            }
        }

        if ("IMPROVING".equals(trend.direction())) {
            strengths.add("RECENT_PERFORMANCE_IMPROVING");
        } else if ("DECLINING".equals(trend.direction())) {
            weaknesses.add("RECENT_PERFORMANCE_DECLINING");
        }

        boolean hasAnyData = !results.isEmpty()
                || practice.questionsAnswered() > 0
                || difficulty.exam().easy().attempted() > 0;
        if (!hasAnyData) {
            weaknesses.add("INSUFFICIENT_DATA");
        }

        return new LearningSignals(
                strengths,
                weaknesses,
                consistency(results));
    }

    private String consistency(List<Result> results) {
        if (results.size() < CONSISTENCY_MIN_EXAMS) {
            return "INSUFFICIENT_DATA";
        }
        double mean = results.stream().mapToDouble(this::percentage).average().orElse(0);
        double variance = results.stream()
                .mapToDouble(r -> Math.pow(percentage(r) - mean, 2))
                .average().orElse(0);
        double stddev = Math.sqrt(variance);
        return stddev <= CONSISTENCY_STABLE_STDDEV ? "STABLE" : "VARIABLE";
    }

    private Trend trend(List<Result> results) {
        if (results.size() < MIN_COMPLETED_EXAMS_FOR_TREND) {
            return new Trend("INSUFFICIENT_DATA", null);
        }
        List<Double> percentages = results.stream().map(this::percentage).toList();
        double recentAverage = windowAverage(percentages, percentages.size() - RECENT_WINDOW, percentages.size());
        double previousAverage = windowAverage(percentages, percentages.size() - RECENT_WINDOW * 2, percentages.size() - RECENT_WINDOW);
        double delta = round(recentAverage - previousAverage);
        String direction;
        if (delta >= MEANINGFUL_DELTA) {
            direction = "IMPROVING";
        } else if (delta <= -MEANINGFUL_DELTA) {
            direction = "DECLINING";
        } else {
            direction = "STABLE";
        }
        return new Trend(direction, delta);
    }

    /** improvementDelta = average of the two most recent exams minus the two before them. */
    private Double improvementDelta(List<Result> results) {
        if (results.size() < MIN_COMPLETED_EXAMS_FOR_TREND) {
            return null;
        }
        List<Double> percentages = results.stream().map(this::percentage).toList();
        double recentAverage = windowAverage(percentages, percentages.size() - RECENT_WINDOW, percentages.size());
        double previousAverage = windowAverage(percentages, percentages.size() - RECENT_WINDOW * 2, percentages.size() - RECENT_WINDOW);
        return round(recentAverage - previousAverage);
    }

    private List<String> windowExamIds(List<Result> results, int window) {
        if (results.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, results.size() - window);
        return results.subList(from, results.size()).stream()
                .map(Result::examId)
                .toList();
    }

    private List<Result> chronologicalResults(String studentId) {
        return resultRepository.findByUserId(studentId).stream()
                .filter(result -> result.total() > 0)
                .sorted(Comparator.comparing(Result::date).thenComparing(Result::examId))
                .toList();
    }

    private double windowAverage(List<Double> values, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += values.get(i);
        }
        return sum / (to - from);
    }

    private Double averagePercentage(List<Result> results) {
        if (results.isEmpty()) {
            return null;
        }
        return round(results.stream().mapToDouble(this::percentage).average().orElse(0));
    }

    private Integer highestPercentage(List<Result> results) {
        return results.stream().mapToInt(this::percentageInt).max().stream().boxed().findFirst().orElse(null);
    }

    private Integer lowestPercentage(List<Result> results) {
        return results.stream().mapToInt(this::percentageInt).min().stream().boxed().findFirst().orElse(null);
    }

    private int percentageInt(Result result) {
        return (int) Math.round(percentage(result));
    }

    private double percentage(Result result) {
        return result.total() <= 0 ? 0.0 : result.score() * 100.0 / result.total();
    }

    private Double percentage(int correct, int attempted) {
        if (attempted <= 0) {
            return null;
        }
        return round(correct * 100.0 / attempted);
    }

    private Double accuracy(AnalyticsRepository.GradedAccuracyRow graded) {
        if (graded == null || graded.graded() <= 0) {
            return null;
        }
        return round(graded.correct() * 100.0 / graded.graded());
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public void requireStudent(User actor) {
        if (actor == null || actor.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Student access is required.");
        }
    }

    private record Counted(int difficulty, int attempted, int correct) {
    }
}
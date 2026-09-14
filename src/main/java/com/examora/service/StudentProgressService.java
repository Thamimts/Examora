package com.examora.service;

import com.examora.dto.LearningIntelligenceDtos.Dimension;
import com.examora.dto.LearningProfileDtos.LearningProfile;
import com.examora.dto.StudentProgressDtos.DifficultyProgress;
import com.examora.dto.StudentProgressDtos.DifficultyProgression;
import com.examora.dto.StudentProgressDtos.ExamHistoryPoint;
import com.examora.dto.StudentProgressDtos.ExamProgress;
import com.examora.dto.StudentProgressDtos.IntelligenceChange;
import com.examora.dto.StudentProgressDtos.Milestone;
import com.examora.dto.StudentProgressDtos.PracticeProgress;
import com.examora.dto.StudentProgressDtos.ProgressDataQuality;
import com.examora.dto.StudentProgressDtos.ProgressTrend;
import com.examora.dto.StudentProgressDtos.StudentProgress;
import com.examora.model.Result;
import com.examora.model.User;
import com.examora.repository.AnalyticsRepository;
import com.examora.repository.AnalyticsRepository.DifficultyRow;
import com.examora.repository.AnalyticsRepository.ExamAccuracyRow;
import com.examora.repository.AnalyticsRepository.ExamDifficultyRow;
import com.examora.repository.AnalyticsRepository.FirstPracticeSessionRow;
import com.examora.repository.AnalyticsRepository.GradedAccuracyRow;
import com.examora.repository.AnalyticsRepository.PracticeAnswerSampleRow;
import com.examora.repository.ResultRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Deterministic Student Improvement &amp; Progress Intelligence.
 *
 * <p>Answers the question "is this student actually improving over time?" using real persisted
 * data only. Recent-vs-previous comparisons use the same sample-based windows as the P5D.1 trend
 * algorithm; practice progress never mixes practice answers with official exam answers; missing
 * history is always reported as INSUFFICIENT_DATA and never faked as zero.
 */
@Service
public class StudentProgressService {

    // ----- trend constants (mirror LearningProfileService) -----
    private static final int RECENT_WINDOW = 2;
    private static final int MIN_COMPLETED_EXAMS_FOR_TREND = RECENT_WINDOW * 2;
    /** |delta| percentage points below this is considered noise (STABLE). */
    private static final double MEANINGFUL_DELTA = 2.0;

    // ----- historical data bounds -----
    /** Maximum exam history points returned (newest kept). */
    private static final int HISTORY_LIMIT = 10;
    /** Bounded practice-answer sample used for the recent/previous split. */
    private static final int PRACTICE_SPLIT_LIMIT = 40;
    /** Minimum observations in EACH practice window before a comparison is claimed. */
    private static final int MIN_PRACTICE_OBSERVATIONS_PER_WINDOW = 5;
    /** Minimum observations in EACH difficulty window before a comparison is claimed. */
    private static final int MIN_DIFFICULTY_OBSERVATIONS_PER_WINDOW = 3;
    /** Minimum observations in EACH exam window before a comparison is claimed. */
    private static final int MIN_EXAM_OBSERVATIONS_PER_WINDOW = 3;

    // ----- intelligence thresholds (mirror LearningIntelligenceService) -----
    private static final double STRENGTH_ACCURACY_PERCENT = 80.0;
    private static final double WEAKNESS_ACCURACY_PERCENT = 60.0;
    /** A weak dimension is "improved" when accuracy rises by at least this many points. */
    private static final double IMPROVEMENT_DELTA_PERCENT = 10.0;

    // ----- difficulty mapping (1-5 model -> buckets) -----
    private static final int EASY_MIN = 1;
    private static final int EASY_MAX = 2;
    private static final int MEDIUM_VALUE = 3;
    private static final int HARD_MIN = 4;

    private static final String INSUFFICIENT = "INSUFFICIENT_DATA";
    private static final String IMPROVING = "IMPROVING";
    private static final String DECLINING = "DECLINING";

    private static final List<String> CHANGE_ORDER = List.of(
            "RECENT_DECLINE",
            "NEW_WEAKNESS",
            "WEAKNESS_IMPROVED",
            "WEAKNESS_PERSISTED",
            "STRENGTH_MAINTAINED");

    private final ResultRepository resultRepository;
    private final AnalyticsRepository analyticsRepository;
    private final LearningProfileService learningProfileService;

    public StudentProgressService(ResultRepository resultRepository,
                                  AnalyticsRepository analyticsRepository,
                                  LearningProfileService learningProfileService) {
        this.resultRepository = resultRepository;
        this.analyticsRepository = analyticsRepository;
        this.learningProfileService = learningProfileService;
    }

    public StudentProgress build(String studentId) {
        return build(studentId, learningProfileService.build(studentId));
    }

    /** Reuses an already-built profile so callers can avoid recomputing the learning profile. */
    public StudentProgress build(String studentId, LearningProfile profile) {
        List<Result> results = chronologicalResults(studentId);

        List<String> recentExamIds = windowExamIds(results, RECENT_WINDOW);
        List<String> previousExamIds = previousWindowExamIds(results);

        ExamTrend examTrend = examTrend(results, profile);

        List<ExamHistoryPoint> history = historyPoints(results, studentId);
        PracticeSplit practice = practiceSplit(studentId);

        Map<String, BucketCounts> recentDifficulty = bucketCounts(
                analyticsRepository.findDifficultyPerformanceForExams(studentId, recentExamIds));
        Map<String, BucketCounts> previousDifficulty = bucketCounts(
                analyticsRepository.findDifficultyPerformanceForExams(studentId, previousExamIds));

        DifficultyProgress difficulty = difficultyProgress(recentDifficulty, previousDifficulty);

        GradedAccuracyRow recentExamAccuracy = analyticsRepository
                .findGradedAccuracyForExams(studentId, recentExamIds);
        GradedAccuracyRow previousExamAccuracy = analyticsRepository
                .findGradedAccuracyForExams(studentId, previousExamIds);

        List<IntelligenceChange> changes = intelligenceChanges(examTrend, recentDifficulty, previousDifficulty,
                recentExamAccuracy, previousExamAccuracy, practice);

        List<Milestone> milestones = milestones(results, studentId);

        ProgressDataQuality dataQuality = new ProgressDataQuality(
                results.size() >= MIN_COMPLETED_EXAMS_FOR_TREND,
                results.size(),
                profile.practice().questionsAnswered());

        ProgressTrend overall = new ProgressTrend(examTrend.direction(), examTrend.recentAverage(),
                examTrend.previousAverage(), examTrend.delta());
        ExamProgress examProgress = new ExamProgress(examTrend.direction(), examTrend.recentAverage(),
                examTrend.previousAverage(), examTrend.delta(), history);
        PracticeProgress practiceProgress = new PracticeProgress(practice.direction(),
                practice.recentAccuracy(), practice.previousAccuracy(), practice.delta(),
                practice.recentCount(), practice.previousCount());

        return new StudentProgress(overall, examProgress, practiceProgress, difficulty, changes, milestones, dataQuality);
    }

    // ----- overall / exam trend -----

    private ExamTrend examTrend(List<Result> results, LearningProfile profile) {
        if (results.size() < MIN_COMPLETED_EXAMS_FOR_TREND) {
            return new ExamTrend(INSUFFICIENT, null, null, null);
        }
        List<Double> percentages = results.stream().map(this::percentage).toList();
        double recentAverage = windowAverage(percentages, percentages.size() - RECENT_WINDOW, percentages.size());
        double previousAverage = windowAverage(percentages, percentages.size() - RECENT_WINDOW * 2,
                percentages.size() - RECENT_WINDOW);
        double delta = round(recentAverage - previousAverage);
        return new ExamTrend(profile.trend().direction(), round(recentAverage), round(previousAverage), delta);
    }

    // ----- exam history -----

    private List<ExamHistoryPoint> historyPoints(List<Result> results, String studentId) {
        if (results.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, results.size() - HISTORY_LIMIT);
        List<Result> window = results.subList(from, results.size());
        List<String> examIds = window.stream().map(Result::examId).filter(Objects::nonNull).toList();

        Map<String, ExamAccuracyRow> accuracy = new HashMap<>();
        for (ExamAccuracyRow row : analyticsRepository.findPerExamGradedAccuracy(studentId, examIds)) {
            accuracy.put(row.examId(), row);
        }
        Map<String, Integer> attemptNumbers = new HashMap<>();
        for (var row : analyticsRepository.findSubmittedAttemptTimingsForExams(studentId, examIds)) {
            attemptNumbers.merge(row.examId(), row.attemptNumber(), Math::max);
        }

        return window.stream().map(result -> {
            ExamAccuracyRow row = accuracy.get(result.examId());
            Double pointAccuracy = row == null || row.graded() <= 0 ? null
                    : round(row.correct() * 100.0 / row.graded());
            return new ExamHistoryPoint(result.examId(), result.examTitle(), result.subject(),
                    result.date(), result.score(), result.total(), round(percentage(result)),
                    pointAccuracy, attemptNumbers.get(result.examId()));
        }).toList();
    }

    // ----- practice progression -----

    private PracticeSplit practiceSplit(String studentId) {
        List<PracticeAnswerSampleRow> rows = analyticsRepository
                .findRecentPracticeAnswerRows(studentId, PRACTICE_SPLIT_LIMIT);
        int n = rows.size();
        int recentCount = n - n / 2;
        int previousCount = n - recentCount;
        if (rows.size() < MIN_PRACTICE_OBSERVATIONS_PER_WINDOW * 2) {
            return new PracticeSplit(INSUFFICIENT, null, null, null, recentCount, previousCount);
        }
        double recentAccuracy = round(correctCount(rows.subList(0, recentCount)) * 100.0 / recentCount);
        double previousAccuracy = round(correctCount(rows.subList(recentCount, n)) * 100.0 / previousCount);
        double delta = round(recentAccuracy - previousAccuracy);
        return new PracticeSplit(direction(delta), recentAccuracy, previousAccuracy, delta, recentCount, previousCount);
    }

    // ----- difficulty progression -----

    private DifficultyProgress difficultyProgress(Map<String, BucketCounts> recent, Map<String, BucketCounts> previous) {
        BucketCounts emptyRecent = new BucketCounts(0, 0);
        return new DifficultyProgress(
                bucketProgression(recent.getOrDefault("easy", emptyRecent), previous.getOrDefault("easy", emptyRecent)),
                bucketProgression(recent.getOrDefault("medium", emptyRecent), previous.getOrDefault("medium", emptyRecent)),
                bucketProgression(recent.getOrDefault("hard", emptyRecent), previous.getOrDefault("hard", emptyRecent)));
    }

    private DifficultyProgression bucketProgression(BucketCounts recent, BucketCounts previous) {
        boolean evaluable = recent.graded() >= MIN_DIFFICULTY_OBSERVATIONS_PER_WINDOW
                && previous.graded() >= MIN_DIFFICULTY_OBSERVATIONS_PER_WINDOW;
        if (!evaluable) {
            return new DifficultyProgression(INSUFFICIENT, null, null, null, recent.graded(), previous.graded());
        }
        double recentAccuracy = round(recent.correct() * 100.0 / recent.graded());
        double previousAccuracy = round(previous.correct() * 100.0 / previous.graded());
        double delta = round(recentAccuracy - previousAccuracy);
        return new DifficultyProgression(direction(delta), recentAccuracy, previousAccuracy, delta,
                recent.graded(), previous.graded());
    }

    // ----- intelligence changes -----

    private List<IntelligenceChange> intelligenceChanges(ExamTrend overall, Map<String, BucketCounts> recentDifficulty,
                                                         Map<String, BucketCounts> previousDifficulty,
                                                         GradedAccuracyRow recentExam, GradedAccuracyRow previousExam,
                                                         PracticeSplit practice) {
        List<IntelligenceChange> changes = new ArrayList<>();

        addDimensionChange(changes, Dimension.EASY_DIFFICULTY,
                windowAccuracy(previousDifficulty, "easy"), windowAccuracy(recentDifficulty, "easy"));
        addDimensionChange(changes, Dimension.MEDIUM_DIFFICULTY,
                windowAccuracy(previousDifficulty, "medium"), windowAccuracy(recentDifficulty, "medium"));
        addDimensionChange(changes, Dimension.HARD_DIFFICULTY,
                windowAccuracy(previousDifficulty, "hard"), windowAccuracy(recentDifficulty, "hard"));

        if (recentExam.graded() >= MIN_EXAM_OBSERVATIONS_PER_WINDOW
                && previousExam.graded() >= MIN_EXAM_OBSERVATIONS_PER_WINDOW) {
            addDimensionChange(changes, Dimension.EXAM_PERFORMANCE,
                    round(previousExam.correct() * 100.0 / previousExam.graded()),
                    round(recentExam.correct() * 100.0 / recentExam.graded()));
        }

        if (practice.direction() != null && !INSUFFICIENT.equals(practice.direction())) {
            addDimensionChange(changes, Dimension.PRACTICE_PERFORMANCE,
                    practice.previousAccuracy(), practice.recentAccuracy());
        }

        if (DECLINING.equals(overall.direction())) {
            changes.add(new IntelligenceChange("RECENT_DECLINE", Dimension.EXAM_PERFORMANCE,
                    overall.previousAverage(), overall.recentAverage(),
                    String.format("Overall exam average declined from %.1f%% to %.1f%%.",
                            overall.previousAverage(), overall.recentAverage())));
        }

        changes.sort(Comparator.comparingInt((IntelligenceChange change) -> CHANGE_ORDER.indexOf(change.type()))
                .thenComparing(change -> change.dimension() == null ? "" : change.dimension().name()));
        return changes;
    }

    private Double windowAccuracy(Map<String, BucketCounts> buckets, String bucket) {
        BucketCounts counts = buckets.get(bucket);
        if (counts == null || counts.graded() < MIN_DIFFICULTY_OBSERVATIONS_PER_WINDOW) {
            return null;
        }
        return round(counts.correct() * 100.0 / counts.graded());
    }

    private void addDimensionChange(List<IntelligenceChange> changes, Dimension dimension,
                                    Double previousAccuracy, Double currentAccuracy) {
        if (previousAccuracy == null || currentAccuracy == null) {
            return;
        }
        boolean previousWeak = previousAccuracy <= WEAKNESS_ACCURACY_PERCENT;
        boolean currentWeak = currentAccuracy <= WEAKNESS_ACCURACY_PERCENT;
        boolean previousStrong = previousAccuracy >= STRENGTH_ACCURACY_PERCENT;
        boolean currentStrong = currentAccuracy >= STRENGTH_ACCURACY_PERCENT;

        if (!previousWeak && currentWeak) {
            changes.add(new IntelligenceChange("NEW_WEAKNESS", dimension, previousAccuracy, currentAccuracy,
                    String.format("%s dropped from %.1f%% to %.1f%% and is now below the %.0f%% weakness threshold.",
                            dimension.name(), previousAccuracy, currentAccuracy, WEAKNESS_ACCURACY_PERCENT)));
        } else if (previousWeak && !currentWeak) {
            changes.add(new IntelligenceChange("WEAKNESS_IMPROVED", dimension, previousAccuracy, currentAccuracy,
                    String.format("%s improved from %.1f%% to %.1f%% and is no longer below the %.0f%% weakness threshold.",
                            dimension.name(), previousAccuracy, currentAccuracy, WEAKNESS_ACCURACY_PERCENT)));
        } else if (previousWeak && currentWeak) {
            if (currentAccuracy >= previousAccuracy + IMPROVEMENT_DELTA_PERCENT) {
                changes.add(new IntelligenceChange("WEAKNESS_IMPROVED", dimension, previousAccuracy, currentAccuracy,
                        String.format("%s improved from %.1f%% to %.1f%% but remains below the %.0f%% weakness threshold.",
                                dimension.name(), previousAccuracy, currentAccuracy, WEAKNESS_ACCURACY_PERCENT)));
            } else {
                changes.add(new IntelligenceChange("WEAKNESS_PERSISTED", dimension, previousAccuracy, currentAccuracy,
                        String.format("%s remained a weakness over the recent exams (%.1f%% to %.1f%%).",
                                dimension.name(), previousAccuracy, currentAccuracy)));
            }
        } else if (previousStrong && currentStrong) {
            changes.add(new IntelligenceChange("STRENGTH_MAINTAINED", dimension, previousAccuracy, currentAccuracy,
                    String.format("%s stayed at or above %.0f%% (%.1f%% to %.1f%%).",
                            dimension.name(), STRENGTH_ACCURACY_PERCENT, previousAccuracy, currentAccuracy)));
        }
    }

    // ----- milestones -----

    private List<Milestone> milestones(List<Result> results, String studentId) {
        List<Milestone> milestones = new ArrayList<>();

        if (!results.isEmpty()) {
            Result first = results.get(0);
            milestones.add(new Milestone("FIRST_COMPLETED_EXAM", first.date(), round(percentage(first))));

            double best = -1.0;
            for (Result result : results) {
                double current = percentage(result);
                if (current > best) {
                    if (best >= 0.0) {
                        milestones.add(new Milestone("IMPROVED_EXAM_PERFORMANCE", result.date(), round(current)));
                    }
                    best = current;
                }
            }
        }

        FirstPracticeSessionRow firstPractice = analyticsRepository.findFirstCompletedPracticeSession(studentId);
        if (firstPractice != null) {
            Double metric = firstPractice.answeredCount() <= 0 ? null
                    : round(firstPractice.correctCount() * 100.0 / firstPractice.answeredCount());
            milestones.add(new Milestone("FIRST_PRACTICE_SESSION",
                    firstPractice.completedAt() == null ? null : firstPractice.completedAt().toString(), metric));
        }

        Map<String, Integer> examOrder = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            examOrder.put(results.get(i).examId(), i);
        }
        Map<String, HardStats> hardPerExam = new HashMap<>();
        for (ExamDifficultyRow row : analyticsRepository.findDifficultyCountsByExam(studentId)) {
            if (row.difficulty() >= HARD_MIN && examOrder.containsKey(row.examId())) {
                hardPerExam.merge(row.examId(), new HardStats(row.graded(), row.correct()), HardStats::add);
            }
        }

        boolean firstHardSuccess = false;
        Double bestHardAccuracy = null;
        for (Result result : results) {
            HardStats stats = hardPerExam.get(result.examId());
            if (stats == null) {
                continue;
            }
            double accuracy = round(stats.correct() * 100.0 / stats.graded());
            if (!firstHardSuccess && stats.correct() >= 1) {
                milestones.add(new Milestone("FIRST_HARD_QUESTION_SUCCESS", result.date(), accuracy));
                firstHardSuccess = true;
            }
            if (bestHardAccuracy != null && accuracy > bestHardAccuracy) {
                milestones.add(new Milestone("IMPROVED_HARD_DIFFICULTY", result.date(), accuracy));
            }
            if (bestHardAccuracy == null || accuracy > bestHardAccuracy) {
                bestHardAccuracy = accuracy;
            }
        }

        milestones.sort(Comparator.comparing(Milestone::occurredAt, Comparator.nullsLast(String::compareTo))
                .thenComparing(Milestone::code));
        return milestones;
    }

    // ----- helpers -----

    private List<Result> chronologicalResults(String studentId) {
        return resultRepository.findByUserId(studentId).stream()
                .filter(result -> result.total() > 0)
                .sorted(Comparator.comparing(Result::date).thenComparing(Result::examId))
                .toList();
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

    private List<String> previousWindowExamIds(List<Result> results) {
        if (results.size() < MIN_COMPLETED_EXAMS_FOR_TREND) {
            return List.of();
        }
        return results.subList(results.size() - RECENT_WINDOW * 2, results.size() - RECENT_WINDOW)
                .stream().map(Result::examId).toList();
    }

    private double windowAverage(List<Double> values, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += values.get(i);
        }
        return sum / (to - from);
    }

    private double percentage(Result result) {
        return result.total() <= 0 ? 0.0 : result.score() * 100.0 / result.total();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String direction(double delta) {
        if (delta >= MEANINGFUL_DELTA) {
            return IMPROVING;
        }
        if (delta <= -MEANINGFUL_DELTA) {
            return DECLINING;
        }
        return "STABLE";
    }

    private Map<String, BucketCounts> bucketCounts(List<DifficultyRow> rows) {
        Map<String, BucketCounts> buckets = new HashMap<>();
        for (DifficultyRow row : rows) {
            buckets.merge(bucketFor(row.difficulty()),
                    new BucketCounts(row.graded(), row.correct()),
                    (a, b) -> new BucketCounts(a.graded() + b.graded(), a.correct() + b.correct()));
        }
        return buckets;
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

    private int correctCount(List<PracticeAnswerSampleRow> rows) {
        int correct = 0;
        for (PracticeAnswerSampleRow row : rows) {
            if (row.correct()) {
                correct++;
            }
        }
        return correct;
    }

    public void requireStudent(User actor) {
        learningProfileService.requireStudent(actor);
    }

    private record ExamTrend(String direction, Double recentAverage, Double previousAverage, Double delta) {
    }

    private record PracticeSplit(String direction, Double recentAccuracy, Double previousAccuracy,
                                 Double delta, int recentCount, int previousCount) {
    }

    private record BucketCounts(int graded, int correct) {
    }

    private record HardStats(int graded, int correct) {
        private static HardStats add(HardStats a, HardStats b) {
            return new HardStats(a.graded() + b.graded(), a.correct() + b.correct());
        }
    }
}
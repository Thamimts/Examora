package com.examora.service;

import com.examora.dto.AnalyticsDtos.ExamAnalyticsDetail;
import com.examora.dto.AnalyticsDtos.ExamAnalyticsSummary;
import com.examora.dto.AnalyticsDtos.ExamAttemptInfo;
import com.examora.dto.AnalyticsDtos.DifficultyPerformance;
import com.examora.dto.AnalyticsDtos.OptionLabelRow;
import com.examora.dto.AnalyticsDtos.OptionDistribution;
import com.examora.dto.AnalyticsDtos.PerExamStudentResult;
import com.examora.dto.AnalyticsDtos.PracticeAnalytics;
import com.examora.dto.AnalyticsDtos.PracticeDifficultyAnalytics;
import com.examora.dto.AnalyticsDtos.QuestionAnalytics;
import com.examora.dto.AnalyticsDtos.RecentExamPoint;
import com.examora.dto.AnalyticsDtos.ScoreDistributionBucket;
import com.examora.dto.AnalyticsDtos.StudentAnalyticsSummary;
import com.examora.dto.AnalyticsDtos.StudentDrilldownAnalytics;
import com.examora.dto.AnalyticsDtos.StudentExamAnalytics;
import com.examora.dto.AnalyticsDtos.StudentPerformanceRow;
import com.examora.dto.AnalyticsDtos.SubjectPerformanceSummary;
import com.examora.exception.ApiException;
import com.examora.model.Exam;
import com.examora.model.Result;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.AnalyticsRepository;
import com.examora.repository.ExamRepository;
import com.examora.repository.ResultRepository;
import com.examora.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private static final int MIN_GRADED_SAMPLE = 5;
    private static final double LOW_ACCURACY_PERCENT = 30.0;
    private static final double HIGH_ACCURACY_PERCENT = 85.0;
    private static final int MIN_UNANSWERED_SAMPLE = 5;
    private static final double HIGH_UNANSWERED_PERCENT = 40.0;
    private static final String FLAG_LOW_SAMPLE = "LOW_SAMPLE_SIZE";
    private static final String FLAG_LOW_ACCURACY = "UNUSUALLY_LOW_ACCURACY";
    private static final String FLAG_HIGH_ACCURACY = "UNUSUALLY_HIGH_ACCURACY";
    private static final String FLAG_HIGH_UNANSWERED = "HIGH_UNANSWERED_RATE";
    private static final List<String> BUCKET_ORDER = List.of("0-19", "20-39", "40-59", "60-79", "80-100");

    private final AnalyticsRepository analyticsRepository;
    private final ExamRepository examRepository;
    private final ResultRepository resultRepository;
    private final UserRepository userRepository;
    private final ExamService examService;

    public AnalyticsService(AnalyticsRepository analyticsRepository, ExamRepository examRepository,
                            ResultRepository resultRepository, UserRepository userRepository,
                            ExamService examService) {
        this.analyticsRepository = analyticsRepository;
        this.examRepository = examRepository;
        this.resultRepository = resultRepository;
        this.userRepository = userRepository;
        this.examService = examService;
    }

    public List<ExamAnalyticsSummary> examSummaries(User actor) {
        requireStaff(actor);
        List<Exam> exams = accessibleExams(actor);
        List<String> examIds = exams.stream().map(Exam::id).toList();
        Map<String, AnalyticsRepository.ExamAggregateRow> aggregates = toMap(
                analyticsRepository.findExamAggregates(examIds), r -> r.examId());
        Map<String, List<Double>> scores = groupScores(
                analyticsRepository.findScoresByExams(examIds));
        Map<String, AnalyticsRepository.AttemptCountRow> attempts = toMap(
                analyticsRepository.findAttemptCountsByExams(examIds), r -> r.examId());
        return exams.stream().map(exam -> {
            AnalyticsRepository.ExamAggregateRow agg = aggregates.get(exam.id());
            AnalyticsRepository.AttemptCountRow attempt = attempts.get(exam.id());
            int submissions = agg == null ? 0 : agg.submissions();
            int participants = agg == null ? 0 : agg.participants();
            return new ExamAnalyticsSummary(
                    exam.id(),
                    exam.title(),
                    exam.subject(),
                    exam.status(),
                    participants,
                    submissions,
                    agg == null ? null : agg.averageScore(),
                    median(scores.getOrDefault(exam.id(), List.of())),
                    agg == null || agg.maxScore() == null ? null : (int) Math.round(agg.maxScore()),
                    agg == null || agg.minScore() == null ? null : (int) Math.round(agg.minScore()),
                    completionRate(attempt),
                    null);
        }).toList();
    }

    public ExamAnalyticsDetail examDetail(String examId, User actor) {
        requireStaff(actor);
        Exam exam = examService.findById(examId);
        examService.requireOwner(examId, actor);
        Map<String, AnalyticsRepository.ExamAggregateRow> aggregates = toMap(
                analyticsRepository.findExamAggregates(List.of(examId)), r -> r.examId());
        AnalyticsRepository.ExamAggregateRow agg = aggregates.get(examId);
        Map<String, List<Double>> scores = groupScores(
                analyticsRepository.findScoresByExams(List.of(examId)));
        AnalyticsRepository.AttemptCountRow attempt = analyticsRepository.findAttemptCountsByExams(List.of(examId))
                .stream().findFirst().orElse(null);
        List<ScoreDistributionBucket> distribution = orderedBuckets(
                analyticsRepository.findScoreDistribution(examId));
        List<AnalyticsRepository.OptionCountRow> optionRows = analyticsRepository.findOptionCounts(examId);
        Map<String, List<String>> optionsByQuestion = optionRows.stream()
                .collect(Collectors.groupingBy(
                        AnalyticsRepository.OptionCountRow::questionId,
                        LinkedHashMap::new,
                        Collectors.mapping(r -> r.optionId(), Collectors.toList())));
        Map<String, Map<String, Integer>> optionCounts = optionRows.stream()
                .collect(Collectors.groupingBy(
                        AnalyticsRepository.OptionCountRow::questionId,
                        LinkedHashMap::new,
                        Collectors.toMap(AnalyticsRepository.OptionCountRow::optionId,
                                AnalyticsRepository.OptionCountRow::count, Integer::sum, LinkedHashMap::new)));
        int eligibleAttempts = attempt == null ? 0 : attempt.submitted();
        List<QuestionAnalytics> questionAnalytics = new ArrayList<>();
        int number = 1;
        for (AnalyticsRepository.QuestionCountRow qc : analyticsRepository.findQuestionCounts(examId)) {
            Double accuracy = qc.graded() == 0 ? null : qc.correct() * 100.0 / qc.graded();
            int unanswered = Math.max(0, eligibleAttempts - qc.graded());
            Double attemptRate = eligibleAttempts == 0 ? null : qc.graded() * 100.0 / eligibleAttempts;
            List<OptionDistribution> optionDistribution = new ArrayList<>();
            for (String optionId : optionsByQuestion.getOrDefault(qc.questionId(), List.of())) {
                optionDistribution.add(new OptionDistribution(optionId,
                        optionCounts.getOrDefault(qc.questionId(), Map.of()).getOrDefault(optionId, 0)));
            }
            questionAnalytics.add(new QuestionAnalytics(
                    number++,
                    qc.questionId(),
                    qc.difficulty(),
                    eligibleAttempts,
                    qc.graded(),
                    qc.correct(),
                    qc.incorrect(),
                    qc.ungraded(),
                    unanswered,
                    accuracy == null ? null : two(accuracy),
                    attemptRate == null ? null : two(attemptRate),
                    optionDistribution,
                    flags(qc.graded(), accuracy, eligibleAttempts, unanswered)));
        }
        return new ExamAnalyticsDetail(
                exam.id(),
                exam.title(),
                exam.subject(),
                exam.status(),
                agg == null ? 0 : agg.participants(),
                agg == null ? 0 : agg.submissions(),
                agg == null ? null : agg.averageScore(),
                median(scores.getOrDefault(examId, List.of())),
                agg == null || agg.maxScore() == null ? null : (int) Math.round(agg.maxScore()),
                agg == null || agg.minScore() == null ? null : (int) Math.round(agg.minScore()),
                completionRate(attempt),
                attempt == null ? 0 : attempt.started(),
                attempt == null ? 0 : attempt.submitted(),
                distribution,
                questionAnalytics);
    }

    public StudentDrilldownAnalytics studentDrilldown(String studentId, User actor) {
        requireStaff(actor);
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Student not found."));
        List<Result> results;
        List<AnalyticsRepository.AttemptTimingRow> timings;
        AnalyticsRepository.GradedAccuracyRow graded;
        if (actor.role() == Role.ADMIN) {
            results = resultRepository.findByUserId(studentId);
            timings = analyticsRepository.findSubmittedAttemptTimings(studentId);
            graded = analyticsRepository.findGradedAccuracy(studentId);
        } else {
            List<String> ownedIds = examService.findOwnedExamIds(actor.id());
            results = resultRepository.findByUserIdInExamIds(studentId, ownedIds);
            timings = analyticsRepository.findSubmittedAttemptTimingsForExams(studentId, ownedIds);
            graded = analyticsRepository.findGradedAccuracyForExams(studentId, ownedIds);
            boolean relevant = !results.isEmpty()
                    || graded.graded() > 0
                    || analyticsRepository.hasAttemptsInExams(studentId, ownedIds);
            if (!relevant) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "This student has no activity in your exams.");
            }
        }
        List<RecentExamPoint> trend = results.stream()
                .limit(5)
                .map(this::toRecentPoint)
                .toList();
        return new StudentDrilldownAnalytics(
                student.id(),
                student.name(),
                results.size(),
                averagePercentage(results),
                highestScore(results),
                lowestScore(results),
                accuracy(graded),
                trend,
                results.stream().map(this::toPerExamResult).toList());
    }

    public StudentAnalyticsSummary studentSummary(String studentId) {
        List<Result> results = resultRepository.findByUserId(studentId);
        AnalyticsRepository.GradedAccuracyRow graded = analyticsRepository.findGradedAccuracy(studentId);
        List<DifficultyPerformance> difficulty = analyticsRepository.findDifficultyPerformance(studentId)
                .stream()
                .map(r -> new DifficultyPerformance(
                        r.difficulty(),
                        r.graded(),
                        r.correct(),
                        r.graded() == 0 ? null : two(r.correct() * 100.0 / r.graded())))
                .toList();
        List<SubjectPerformanceSummary> subjects = analyticsRepository.findSubjectPerformance(studentId)
                .stream()
                .map(r -> new SubjectPerformanceSummary(r.subject(), r.completed(),
                        r.averagePercentage() == null ? 0.0 : r.averagePercentage()))
                .toList();
        return new StudentAnalyticsSummary(
                results.size(),
                averagePercentage(results),
                highestScore(results),
                lowestScore(results),
                graded.graded(),
                graded.correct(),
                accuracy(graded),
                results.stream().limit(5).map(this::toRecentPoint).toList(),
                subjects,
                difficulty,
                practiceAnalytics(studentId, null));
    }

    public List<StudentExamAnalytics> studentExams(String studentId) {
        List<Result> results = resultRepository.findByUserId(studentId);
        Map<String, ExamAttemptInfo> attemptInfo = latestAttemptInfo(
                analyticsRepository.findSubmittedAttemptTimings(studentId));
        return results.stream().map(result -> {
            ExamAttemptInfo info = attemptInfo.get(result.examId());
            return new StudentExamAnalytics(
                    result.examId(),
                    result.examTitle(),
                    result.subject(),
                    result.date(),
                    result.score(),
                    result.total(),
                    percentage(result),
                    info,
                    practiceAnalytics(studentId, result.examId()));
        }).toList();
    }

    private PracticeAnalytics practiceAnalytics(String studentId, String examId) {
        int totalSessions = analyticsRepository.countPracticeSessions(studentId, examId);
        int completedSessions = analyticsRepository.countCompletedPracticeSessions(studentId, examId);
        AnalyticsRepository.PracticeAnswerRow answers = analyticsRepository.findPracticeAnswerAggregates(studentId, examId);
        List<PracticeDifficultyAnalytics> byDifficulty = analyticsRepository.findPracticeDifficulty(studentId, examId)
                .stream()
                .map(r -> new PracticeDifficultyAnalytics(
                        r.difficulty(),
                        r.questions(),
                        r.correct(),
                        r.questions() == 0 ? null : two(r.correct() * 100.0 / r.questions())))
                .toList();
        Double accuracy = answers.questions() == 0 ? null : two(answers.correct() * 100.0 / answers.questions());
        Instant activity = analyticsRepository.findMostRecentPracticeActivity(studentId, examId);
        return new PracticeAnalytics(
                totalSessions,
                completedSessions,
                answers.questions(),
                answers.correct(),
                accuracy,
                byDifficulty,
                activity == null ? null : activity.toString());
    }

    private List<String> flags(int graded, Double accuracy, int eligible, int unanswered) {
        List<String> result = new ArrayList<>();
        if (graded > 0 && graded < MIN_GRADED_SAMPLE) {
            result.add(FLAG_LOW_SAMPLE);
        } else if (graded >= MIN_GRADED_SAMPLE && accuracy != null) {
            if (accuracy < LOW_ACCURACY_PERCENT) {
                result.add(FLAG_LOW_ACCURACY);
            }
            if (accuracy > HIGH_ACCURACY_PERCENT) {
                result.add(FLAG_HIGH_ACCURACY);
            }
        }
        if (eligible >= MIN_UNANSWERED_SAMPLE && unanswered > 0
                && (double) unanswered / eligible * 100.0 > HIGH_UNANSWERED_PERCENT) {
            result.add(FLAG_HIGH_UNANSWERED);
        }
        return result;
    }

    private List<Exam> accessibleExams(User actor) {
        if (actor.role() == Role.ADMIN) {
            return examRepository.findAll();
        }
        List<String> ownedIds = examService.findOwnedExamIds(actor.id());
        return examRepository.findByIds(ownedIds).stream()
                .sorted(Comparator.comparing(Exam::date))
                .toList();
    }

    private Double completionRate(AnalyticsRepository.AttemptCountRow attempt) {
        if (attempt == null || attempt.started() == 0) {
            return null;
        }
        return two(attempt.submitted() * 100.0 / attempt.started());
    }

    private Double median(List<Double> percentages) {
        if (percentages.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(percentages);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return two((sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0);
    }

    private Map<String, List<Double>> groupScores(List<AnalyticsRepository.ScoreRow> scores) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (AnalyticsRepository.ScoreRow score : scores) {
            grouped.computeIfAbsent(score.examId(), k -> new ArrayList<>())
                    .add(score.percentage() == null ? 0.0 : score.percentage());
        }
        return grouped;
    }

    private <T> Map<String, T> toMap(List<T> rows, Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T row : rows) {
            map.put(key.apply(row), row);
        }
        return map;
    }

    private List<ScoreDistributionBucket> orderedBuckets(List<AnalyticsRepository.ScoreDistributionRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AnalyticsRepository.ScoreDistributionRow row : rows) {
            counts.put(row.range(), row.count());
        }
        List<ScoreDistributionBucket> buckets = new ArrayList<>();
        for (String range : BUCKET_ORDER) {
            buckets.add(new ScoreDistributionBucket(range, counts.getOrDefault(range, 0)));
        }
        return buckets;
    }

    private Map<String, ExamAttemptInfo> latestAttemptInfo(List<AnalyticsRepository.AttemptTimingRow> timings) {
        Map<String, ExamAttemptInfo> latest = new LinkedHashMap<>();
        for (AnalyticsRepository.AttemptTimingRow timing : timings) {
            if (!latest.containsKey(timing.examId())) {
                Double duration = timing.startedAt() == null || timing.submittedAt() == null
                        ? null
                        : Math.max(0, (timing.submittedAt().toEpochMilli() - timing.startedAt().toEpochMilli()) / 1000.0);
                latest.put(timing.examId(), new ExamAttemptInfo(
                        timing.attemptNumber(),
                        duration == null ? null : (int) Math.round(duration),
                        timing.submittedAt() == null ? null : timing.submittedAt().toString()));
            }
        }
        return latest;
    }

    private RecentExamPoint toRecentPoint(Result result) {
        return new RecentExamPoint(
                result.examId(),
                result.examTitle(),
                result.subject(),
                result.date(),
                result.score(),
                result.total(),
                two(percentage(result)));
    }

    private PerExamStudentResult toPerExamResult(Result result) {
        return new PerExamStudentResult(
                result.examId(),
                result.examTitle(),
                result.subject(),
                result.date(),
                result.score(),
                result.total(),
                two(percentage(result)));
    }

    private Double averagePercentage(List<Result> results) {
        if (results.isEmpty()) {
            return null;
        }
        double sum = results.stream().mapToDouble(this::percentage).sum();
        return two(sum / results.size());
    }

    private Integer highestScore(List<Result> results) {
        return results.stream().mapToInt(this::percentageInt).max().stream().boxed().findFirst().orElse(null);
    }

    private Integer lowestScore(List<Result> results) {
        return results.stream().mapToInt(this::percentageInt).min().stream().boxed().findFirst().orElse(null);
    }

    private int percentageInt(Result result) {
        return (int) Math.round(percentage(result));
    }

    private double percentage(Result result) {
        return result.total() <= 0 ? 0.0 : result.score() * 100.0 / result.total();
    }

    private Double accuracy(AnalyticsRepository.GradedAccuracyRow graded) {
        if (graded == null || graded.graded() == 0) {
            return null;
        }
        return two(graded.correct() * 100.0 / graded.graded());
    }

    public List<StudentPerformanceRow> studentPerformance(String examId, User actor) {
        requireStaff(actor);
        examService.requireOwner(examId, actor);
        Map<String, AnalyticsRepository.StudentPracticeRow> practice = analyticsRepository
                .findPracticeAggregatesForExam(examId).stream()
                .collect(Collectors.toMap(AnalyticsRepository.StudentPracticeRow::studentId, r -> r));
        return analyticsRepository.findStudentPerformanceRows(examId).stream().map(row -> {
            Double percentage = row.total() == null || row.total() == 0
                    ? null
                    : two(row.score() * 100.0 / row.total());
            Integer duration = toDurationSeconds(row);
            AnalyticsRepository.StudentPracticeRow practiceRow = practice.get(row.studentId());
            int questions = practiceRow == null ? 0 : practiceRow.questions();
            int correct = practiceRow == null ? 0 : practiceRow.correct();
            Double practiceAccuracy = questions == 0 ? null : two(correct * 100.0 / questions);
            return new StudentPerformanceRow(
                    row.studentId(),
                    row.studentName(),
                    row.hasResult(),
                    row.score(),
                    row.total(),
                    percentage,
                    row.submittedAt() == null ? null : row.submittedAt().toString(),
                    duration,
                    row.attemptNumber(),
                    row.attemptStatus(),
                    row.activeNow(),
                    questions,
                    correct,
                    practiceAccuracy);
        }).toList();
    }

    public List<OptionLabelRow> optionLabels(String examId, User actor) {
        requireStaff(actor);
        examService.requireOwner(examId, actor);
        return analyticsRepository.findOptionLabels(examId).stream()
                .map(row -> new OptionLabelRow(row.questionId(), row.optionId(), row.text(), row.displayOrder()))
                .toList();
    }

    private Integer toDurationSeconds(AnalyticsRepository.StudentPerformanceRow row) {
        if (row.startedAt() == null || row.submittedAt() == null) {
            return null;
        }
        long seconds = Math.max(0, row.submittedAt().toEpochMilli() - row.startedAt().toEpochMilli()) / 1000;
        return (int) seconds;
    }

    private double two(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void requireStaff(User actor) {
        if (actor == null || (actor.role() != Role.TEACHER && actor.role() != Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Teacher or administrator access is required.");
        }
    }
}
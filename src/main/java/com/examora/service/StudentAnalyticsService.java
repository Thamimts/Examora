package com.examora.service;

import com.examora.dto.StudentPerformanceAnalytics;
import com.examora.dto.StudentPerformanceAnalytics.ScoreTrendPoint;
import com.examora.dto.StudentPerformanceAnalytics.SubjectPerformance;
import com.examora.dto.StudentPerformanceAnalytics.TopicPerformance;
import com.examora.model.Result;
import com.examora.repository.ResultRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StudentAnalyticsService {
    private final ResultRepository resultRepository;

    public StudentAnalyticsService(ResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    public StudentPerformanceAnalytics getPerformance(String studentId) {
        List<Result> results = resultRepository.findByUserId(studentId);
        if (results.isEmpty()) {
            return new StudentPerformanceAnalytics(0, 0, null, null, 0, List.of(), List.of(), List.of());
        }

        double average = results.stream().mapToDouble(this::percentage).average().orElse(0);
        int highest = results.stream().mapToInt(this::percentageAsInt).max().orElse(0);
        int lowest = results.stream().mapToInt(this::percentageAsInt).min().orElse(0);
        double accuracy = results.stream().mapToDouble(this::accuracy).average().orElse(0);

        List<ScoreTrendPoint> trend = results.stream()
                .sorted(Comparator.comparing(Result::date, Comparator.nullsLast(String::compareTo)))
                .limit(10)
                .map(result -> new ScoreTrendPoint(result.id(), result.examTitle(), result.date(), round(percentage(result))))
                .toList();

        Map<String, List<Result>> bySubject = new LinkedHashMap<>();
        results.forEach(result -> bySubject.computeIfAbsent(result.subject(), ignored -> new ArrayList<>()).add(result));
        List<SubjectPerformance> subjects = bySubject.entrySet().stream()
                .map(entry -> new SubjectPerformance(entry.getKey(), entry.getValue().size(),
                        round(entry.getValue().stream().mapToDouble(this::percentage).average().orElse(0)),
                        round(entry.getValue().stream().mapToDouble(this::accuracy).average().orElse(0))))
                .toList();

        return new StudentPerformanceAnalytics(results.size(), round(average), highest, lowest, round(accuracy), trend, subjects, List.<TopicPerformance>of());
    }

    private double percentage(Result result) {
        return result.total() <= 0 ? 0 : result.score() * 100.0 / result.total();
    }

    private double accuracy(Result result) {
        return percentage(result);
    }

    private int percentageAsInt(Result result) {
        return (int) Math.round(percentage(result));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

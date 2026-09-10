package com.examora.service;

import com.examora.dto.AdaptivePracticeDtos.PracticeDifficultyStat;
import com.examora.dto.AdaptivePracticeDtos.PracticeSummaryDto;
import com.examora.dto.StudentPerformanceAnalytics.SubjectPerformance;
import com.examora.model.PracticeAnswer;
import com.examora.model.Question;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveSelectionService {

    private static final double MIN_DIFFICULTY = 1.0;
    private static final double MAX_DIFFICULTY = 5.0;
    private static final double STEP = 0.5;

    public double seedWorkingLevel(Double subjectAccuracy, Double overallAccuracy) {
        double accuracy = subjectAccuracy != null ? subjectAccuracy
                : (overallAccuracy != null ? overallAccuracy : 50.0);
        return clamp(3.0 + (accuracy - 50.0) / 25.0, MIN_DIFFICULTY, MAX_DIFFICULTY);
    }

    public double updateWorkingLevel(double workingLevel, boolean correct) {
        return clamp(workingLevel + (correct ? STEP : -STEP), MIN_DIFFICULTY, MAX_DIFFICULTY);
    }

    public int level(double workingLevel) {
        return (int) Math.round(clamp(workingLevel, MIN_DIFFICULTY, MAX_DIFFICULTY));
    }

    public Optional<Question> selectNext(List<Question> remaining, double workingLevel) {
        int targetLevel = level(workingLevel);
        return remaining.stream()
                .min(Comparator.<Question>comparingInt(q -> Math.abs(q.difficulty() - targetLevel))
                        .thenComparing(Question::id));
    }

    public PracticeSummaryDto summarize(int correctCount, int totalAnswered, List<PracticeAnswer> answers,
                                        String subject, List<SubjectPerformance> subjectPerformance) {
        TreeMap<Integer, int[]> counts = new TreeMap<>();
        for (PracticeAnswer answer : answers) {
            counts.computeIfAbsent(answer.difficulty(), ignored -> new int[2]);
            int[] entry = counts.get(answer.difficulty());
            entry[0]++;
            if (answer.correct()) {
                entry[1]++;
            }
        }

        List<PracticeDifficultyStat> perDifficulty = new ArrayList<>();
        List<String> weakAreas = new ArrayList<>();
        List<String> strongAreas = new ArrayList<>();
        for (Map.Entry<Integer, int[]> entry : counts.entrySet()) {
            int difficulty = entry.getKey();
            int answered = entry.getValue()[0];
            int correct = entry.getValue()[1];
            double accuracy = round(answered == 0 ? 0 : correct * 100.0 / answered);
            perDifficulty.add(new PracticeDifficultyStat(difficulty, answered, correct, accuracy));
            if (accuracy < 50.0) {
                weakAreas.add("Level " + difficulty + " questions");
            } else if (accuracy >= 70.0) {
                strongAreas.add("Level " + difficulty + " questions");
            }
        }

        String subjectFocus = (subject == null || subjectPerformance == null) ? null
                : subjectPerformance.stream()
                        .filter(perf -> subject.equalsIgnoreCase(perf.subject()))
                        .findFirst()
                        .map(perf -> "Your average in " + perf.subject() + " is " + perf.averageScore() + "%.")
                        .orElse(null);

        return new PracticeSummaryDto(correctCount, totalAnswered,
                round(totalAnswered == 0 ? 0 : correctCount * 100.0 / totalAnswered),
                perDifficulty, weakAreas, strongAreas, subjectFocus);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
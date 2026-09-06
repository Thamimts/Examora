package com.examora.dto;

import java.util.List;

public record StudentPerformanceAnalytics(
        int completedExamCount,
        double averageScore,
        Integer highestScore,
        Integer lowestScore,
        double accuracy,
        List<ScoreTrendPoint> recentScoreTrend,
        List<SubjectPerformance> subjectPerformance,
        List<TopicPerformance> topicPerformance) {

    public record ScoreTrendPoint(String resultId, String examTitle, String date, double score) {}

    public record SubjectPerformance(String subject, int completedExamCount, double averageScore, double accuracy) {}

    public record TopicPerformance(String topic, int completedExamCount, double averageScore, double accuracy) {}
}

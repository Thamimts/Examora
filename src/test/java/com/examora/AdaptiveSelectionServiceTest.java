package com.examora;

import com.examora.dto.AdaptivePracticeDtos.PracticeSummaryDto;
import com.examora.dto.StudentPerformanceAnalytics.SubjectPerformance;
import com.examora.model.PracticeAnswer;
import com.examora.model.Question;
import com.examora.service.AdaptiveSelectionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveSelectionServiceTest {

    private final AdaptiveSelectionService selection = new AdaptiveSelectionService();

    private Question question(String id, int difficulty) {
        return new Question(id, "exam-1", "Q " + id, List.of("A", "B"), "A", difficulty);
    }

    @Test
    void seedsThreePointZeroWhenNoResultsAreAvailable() {
        assertThat(selection.seedWorkingLevel(null, null)).isEqualTo(3.0);
    }

    @Test
    void seedUsesSubjectAccuracyWhenPresentOverOverall() {
        assertThat(selection.seedWorkingLevel(80.0, 50.0)).isEqualTo(4.2);
    }

    @Test
    void seedUsesOverallAccuracyWhenSubjectUnavailable() {
        assertThat(selection.seedWorkingLevel(null, 100.0)).isEqualTo(5.0);
        assertThat(selection.seedWorkingLevel(null, 0.0)).isEqualTo(1.0);
    }

    @Test
    void seedClampsToBounds() {
        assertThat(selection.seedWorkingLevel(200.0, null)).isEqualTo(5.0);
        assertThat(selection.seedWorkingLevel(-50.0, null)).isEqualTo(1.0);
    }

    @Test
    void workingLevelMovesByHalfAndClamps() {
        assertThat(selection.updateWorkingLevel(3.0, true)).isEqualTo(3.5);
        assertThat(selection.updateWorkingLevel(3.0, false)).isEqualTo(2.5);
        assertThat(selection.updateWorkingLevel(5.0, true)).isEqualTo(5.0);
        assertThat(selection.updateWorkingLevel(1.0, false)).isEqualTo(1.0);
    }

    @Test
    void levelRoundsToNearestInteger() {
        assertThat(selection.level(3.0)).isEqualTo(3);
        assertThat(selection.level(3.5)).isEqualTo(4);
        assertThat(selection.level(4.6)).isEqualTo(5);
        assertThat(selection.level(1.2)).isEqualTo(1);
    }

    @Test
    void selectNextPicksNearestDifficultyToCurrentLevel() {
        List<Question> remaining = List.of(question("a", 1), question("b", 3), question("c", 5));
        Optional<Question> chosen = selection.selectNext(remaining, 3.4);
        assertThat(chosen).isPresent();
        assertThat(chosen.get().difficulty()).isEqualTo(3);
    }

    @Test
    void selectNextTieBreaksByIdAscending() {
        List<Question> remaining = List.of(question("b", 3), question("a", 3));
        Optional<Question> chosen = selection.selectNext(remaining, 2.8);
        assertThat(chosen).isPresent();
        assertThat(chosen.get().id()).isEqualTo("a");
    }

    @Test
    void selectNextExcludesQuestionsAlreadyAnswered() {
        List<Question> bank = List.of(question("a", 3), question("b", 4));
        Question first = selection.selectNext(bank, 3.0).orElseThrow();
        List<Question> remaining = bank.stream().filter(q -> !q.id().equals(first.id())).toList();
        Optional<Question> second = selection.selectNext(remaining, 3.5);
        assertThat(second).isPresent();
        assertThat(second.get().id()).isNotEqualTo(first.id());
    }

    @Test
    void summarizeComputesBandStatsWeakAndStrongAreasAndSubjectFocus() {
        List<PracticeAnswer> answers = List.of(
                answer("q1", 1, true),
                answer("q2", 1, true),
                answer("q3", 2, false),
                answer("q4", 2, false),
                answer("q5", 3, true),
                answer("q6", 3, true),
                answer("q7", 3, true));
        List<SubjectPerformance> subjectPerformance = List.of(
                new SubjectPerformance("Math", 2, 72.5, 72.5));

        PracticeSummaryDto summary = selection.summarize(5, 7, answers, "Math", subjectPerformance);

        assertThat(summary.correctCount()).isEqualTo(5);
        assertThat(summary.totalAnswered()).isEqualTo(7);
        assertThat(summary.accuracy()).isEqualTo(71.43);
        assertThat(summary.perDifficulty()).hasSize(3);
        assertThat(summary.perDifficulty().get(0).difficulty()).isEqualTo(1);
        assertThat(summary.perDifficulty().get(0).accuracy()).isEqualTo(100.0);
        assertThat(summary.weakAreas()).containsExactly("Level 2 questions");
        assertThat(summary.strongAreas()).containsExactly("Level 1 questions", "Level 3 questions");
        assertThat(summary.subjectFocus()).isEqualTo("Your average in Math is 72.5%.");
    }

    @Test
    void summarizeHasNoSubjectFocusWhenSubjectPerformanceMissing() {
        PracticeSummaryDto summary = selection.summarize(0, 1, List.of(answer("q1", 1, false)), "Science", List.of());
        assertThat(summary.subjectFocus()).isNull();
    }

    private PracticeAnswer answer(String questionId, int difficulty, boolean correct) {
        return new PracticeAnswer("a-" + questionId, "session-1", questionId, "opt-1", "A", correct,
                difficulty, 0, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
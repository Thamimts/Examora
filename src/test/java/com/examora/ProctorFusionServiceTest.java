package com.examora;

import com.examora.service.ProctorFusionConfig;
import com.examora.service.ProctorFusionService;
import com.examora.service.ProctorFusionService.Contribution;
import com.examora.service.ProctorFusionService.FusionResult;
import com.examora.service.ProctorFusionService.FusionSignal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProctorFusionServiceTest {

    private static final Instant REF = Instant.parse("2026-09-18T12:00:00Z");
    private final ProctorFusionService service = new ProctorFusionService();

    @Test
    void emptySignalsProduceZeroScore() {
        FusionResult result = service.fuse(List.of(), REF);

        assertThat(result.algorithmVersion()).isEqualTo("fusion-v1");
        assertThat(result.evidenceCount()).isZero();
        assertThat(result.baselineScore()).isZero();
        assertThat(result.fusedScore()).isZero();
        assertThat(result.fusedConfidence()).isZero();
        assertThat(result.contributingSignals()).isEmpty();
        assertThat(result.windowStart()).isEqualTo(REF.minusMillis(ProctorFusionConfig.TEMPORAL_WINDOW_MS).toString());
        assertThat(result.windowEnd()).isEqualTo(REF.toString());
    }

    @Test
    void singleBrowserSignalWithoutConfidenceUsesDocumentedFallback() {
        FusionResult result = service.fuse(List.of(signal("e-1", "TAB_SWITCH", "BROWSER", null)), REF);

        assertThat(result.evidenceCount()).isEqualTo(1);
        assertThat(result.baselineScore()).isCloseTo(1.0 / 3, within(1e-9));
        Contribution contribution = result.contributingSignals().getFirst();
        assertThat(contribution.baseWeight()).isEqualTo(0.6);
        assertThat(contribution.sourceWeight()).isEqualTo(0.8);
        assertThat(contribution.confidence()).isEqualTo(0.9);
        assertThat(contribution.contribution()).isCloseTo(0.6 * 0.8 * 0.9, within(1e-9));
        assertThat(result.fusedScore()).isCloseTo(0.432, within(1e-9));
        assertThat(result.fusedConfidence()).isCloseTo(0.9, within(1e-9));
    }

    @Test
    void aiSignalWithZeroConfidenceContributesNothing() {
        FusionResult result = service.fuse(List.of(signal("e-1", "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.0)), REF);

        assertThat(result.evidenceCount()).isEqualTo(1);
        assertThat(result.contributingSignals().getFirst().contribution()).isZero();
        assertThat(result.fusedScore()).isZero();
        assertThat(result.fusedConfidence()).isZero();
    }

    @Test
    void aiSignalWithFullConfidenceContributesBaseAndSourceWeights() {
        FusionResult result = service.fuse(List.of(signal("e-1", "FACE_COUNT_ANOMALY", "CLIENT_AI", 1.0)), REF);

        Contribution contribution = result.contributingSignals().getFirst();
        assertThat(contribution.baseWeight()).isEqualTo(0.7);
        assertThat(contribution.sourceWeight()).isEqualTo(1.0);
        assertThat(contribution.contribution()).isCloseTo(0.7, within(1e-9));
        assertThat(result.fusedConfidence()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void aiSignalWithHalfConfidenceScalesContribution() {
        FusionResult result = service.fuse(List.of(signal("e-1", "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.5)), REF);

        assertThat(result.fusedScore()).isCloseTo(0.35, within(1e-9));
        assertThat(result.fusedConfidence()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void heterogeneousSignalsCombineAndCapAtOne() {
        FusionResult result = service.fuse(List.of(
                signal("e-1", "TAB_SWITCH", "BROWSER", null),
                signal("e-2", "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.92),
                signal("e-3", "WINDOW_BLUR", "BROWSER", null)), REF);

        double expected = 0.6 * 0.8 * 0.9 + 0.7 * 1.0 * 0.92 + 0.7 * 0.8 * 0.9;
        assertThat(expected).isGreaterThan(1.0);
        assertThat(result.fusedScore()).isEqualTo(1.0);
        assertThat(result.evidenceCount()).isEqualTo(3);
        assertThat(result.baselineScore()).isEqualTo(1.0);
        assertThat(result.fusedConfidence()).isCloseTo((0.9 + 0.92 + 0.9) / 3, within(1e-9));
    }

    @Test
    void duplicateEventsAreCountedOnce() {
        List<FusionSignal> duplicated = List.of(
                signal("e-1", "TAB_SWITCH", "BROWSER", null),
                signal("e-1", "TAB_SWITCH", "BROWSER", null));

        FusionResult result = service.fuse(duplicated, REF);

        assertThat(result.evidenceCount()).isEqualTo(1);
        assertThat(result.contributingSignals()).hasSize(1);
        assertThat(result.fusedScore()).isCloseTo(0.432, within(1e-9));
    }

    @Test
    void eventsOutsideWindowAreIgnored() {
        FusionSignal oldSignal = signal("e-old", "TAB_SWITCH", "BROWSER", null);
        // old: 60s before the reference, outside the 30s window
        FusionSignal outside = new FusionSignal(oldSignal.id(), oldSignal.type(), oldSignal.source(),
                REF.minusMillis(60_000).toString(), oldSignal.confidence(), oldSignal.durationMs());

        FusionResult result = service.fuse(List.of(outside), REF);

        assertThat(result.evidenceCount()).isZero();
        assertThat(result.fusedScore()).isZero();
    }

    @Test
    void eventsInsideWindowParticipate() {
        FusionSignal signal = new FusionSignal("e-1", "TAB_SWITCH", "BROWSER",
                REF.minusMillis(20_000).toString(), null, null);

        FusionResult result = service.fuse(List.of(signal), REF);

        assertThat(result.evidenceCount()).isEqualTo(1);
        assertThat(result.fusedScore()).isCloseTo(0.432, within(1e-9));
    }

    @Test
    void mixedAiAndBrowserEvidenceContributeTogether() {
        FusionResult result = service.fuse(List.of(
                signal("e-ai", "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.8),
                signal("e-browser", "MULTIPLE_FACES", "BROWSER", null)), REF);

        double expected = 0.7 * 1.0 * 0.8 + 0.9 * 0.8 * 0.9;
        assertThat(result.fusedScore()).isCloseTo(Math.min(1.0, expected), within(1e-9));
        assertThat(result.evidenceCount()).isEqualTo(2);
        assertThat(result.baselineScore()).isCloseTo(2.0 / 3, within(1e-9));
    }

    @Test
    void scoresStayWithinUnitInterval() {
        List<FusionSignal> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(signal("e-" + i, i % 2 == 0 ? "MULTIPLE_FACES" : "FULLSCREEN_EXIT", "BROWSER", 1.0));
        }

        FusionResult result = service.fuse(many, REF);

        assertThat(result.fusedScore()).isEqualTo(1.0);
        assertThat(result.baselineScore()).isEqualTo(1.0);
        assertThat(result.contributingSignals()).allSatisfy(c -> {
            assertThat(c.contribution()).isBetween(0.0, 1.0);
            assertThat(c.confidence()).isBetween(0.0, 1.0);
            assertThat(c.baseWeight()).isBetween(0.0, 1.0);
            assertThat(c.sourceWeight()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void repeatedCalculationIsDeterministicAndOrderIndependent() {
        List<FusionSignal> first = List.of(
                signal("e-1", "TAB_SWITCH", "BROWSER", null),
                signal("e-2", "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.92),
                signal("e-3", "WINDOW_BLUR", "BROWSER", null));
        List<FusionSignal> shuffled = List.of(
                signal("e-3", "WINDOW_BLUR", "BROWSER", null),
                signal("e-1", "TAB_SWITCH", "BROWSER", null),
                signal("e-2", "FACE_COUNT_ANOMALY", "CLIENT_AI", 0.92));

        FusionResult a = service.fuse(first, REF);
        FusionResult b = service.fuse(shuffled, REF);

        assertThat(b).isEqualTo(a);
        assertThat(a.contributingSignals().getFirst().id()).isEqualTo("e-1");
    }

    @Test
    void resultCarriesAlgorithmVersion() {
        FusionResult result = service.fuse(List.of(signal("e-1", "TAB_SWITCH", "BROWSER", null)), REF);

        assertThat(result.algorithmVersion()).isEqualTo("fusion-v1");
    }

    @Test
    void malformedAndMissingConfidenceFallBackDeterministically() {
        FusionSignal noConfidence = signal("e-1", "TAB_SWITCH", "BROWSER", null);
        FusionSignal nanConfidence = signal("e-2", "TAB_SWITCH", "BROWSER", Double.NaN);
        FusionSignal negativeConfidence = signal("e-3", "TAB_SWITCH", "BROWSER", -0.5);
        FusionSignal oversizedConfidence = signal("e-4", "TAB_SWITCH", "BROWSER", 1.5);
        FusionSignal aiNoConfidence = signal("e-5", "FACE_COUNT_ANOMALY", "CLIENT_AI", null);
        FusionSignal badTimestamp = new FusionSignal("e-6", "TAB_SWITCH", "BROWSER", "not-a-timestamp", null, null);

        FusionResult result = service.fuse(List.of(noConfidence, nanConfidence, negativeConfidence,
                oversizedConfidence, aiNoConfidence, badTimestamp), REF);

        assertThat(result.evidenceCount()).isEqualTo(5);
        double contributions = result.contributingSignals().stream()
                .mapToDouble(Contribution::contribution).sum();
        assertThat(contributions).isGreaterThan(1.0);
        assertThat(result.fusedScore()).isEqualTo(1.0);
        ProctorFusionService.Contribution first = result.contributingSignals().getFirst();
        assertThat(first.confidence()).isEqualTo(0.9);
        assertThat(result.contributingSignals().get(4).confidence()).isEqualTo(0.7);
    }

    @Test
    void futureAiSignalTypesAreRecognizedAsResearchEvidence() {
        List<FusionSignal> aiSignals = List.of(
                signal("a-1", "PHONE_DETECTED", "CLIENT_AI", 1.0),
                signal("a-2", "UNKNOWN_OBJECT", "CLIENT_AI", 1.0),
                signal("a-3", "GAZE_ANOMALY", "CLIENT_AI", 1.0),
                signal("a-4", "HEAD_POSE_ANOMALY", "CLIENT_AI", 1.0));

        FusionResult result = service.fuse(aiSignals, REF);

        assertThat(result.evidenceCount()).isEqualTo(4);
        assertThat(result.contributingSignals()).allSatisfy(c ->
                assertThat(c.contribution()).isEqualTo(c.baseWeight() * c.sourceWeight()));
    }

    private FusionSignal signal(String id, String type, String source, Double confidence) {
        return new FusionSignal(id, type, source, REF.toString(), confidence, null);
    }
}
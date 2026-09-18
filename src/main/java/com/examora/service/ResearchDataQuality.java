package com.examora.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure counting of research data-quality indicators. All counts are derived from the
 * supplied per-sample facts; invalid research data is counted, never silently discarded.
 */
public final class ResearchDataQuality {

    private ResearchDataQuality() {}

    public record Entry(String sampleId, String scenario, String resolvedLabel,
                        boolean reviewed, boolean invalid, boolean linked, boolean hasSignals,
                        boolean hasCondition, boolean hasMeasuredLatency,
                        boolean hasMeasuredBandwidth) {
    }

    public record ScenarioOutcome(String scenario, String label, String expectedLabel,
                                  int sampleCount, int resolvedCount, int agreementCount) {
    }

    public record Report(int registered, int evaluable, int unreviewed, int tied, int invalid,
                         int scenarioGroundTruthAgreement, int missingSignals, int missingCondition,
                         int missingMeasuredLatency, int missingBandwidth,
                         List<ScenarioOutcome> scenarios) {
    }

    public static Report compute(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new Report(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
        int registered = 0;
        int evaluable = 0;
        int unreviewed = 0;
        int tied = 0;
        int invalid = 0;
        int agreement = 0;
        int missingSignals = 0;
        int missingCondition = 0;
        int missingMeasuredLatency = 0;
        int missingBandwidth = 0;

        Map<String, ScenarioCounter> counters = new LinkedHashMap<>();
        for (Entry entry : entries) {
            registered++;
            boolean resolved = entry.reviewed() && entry.resolvedLabel() != null;
            boolean isEvaluable = resolved && !entry.invalid();
            if (!entry.reviewed()) {
                unreviewed++;
            }
            if (entry.reviewed() && entry.resolvedLabel() == null) {
                tied++;
            }
            if (entry.invalid()) {
                invalid++;
            }
            if (isEvaluable) {
                evaluable++;
                if (entry.scenario() != null && entry.scenario().equals(entry.resolvedLabel())) {
                    agreement++;
                }
            }
            if (entry.linked() && !entry.hasSignals()) {
                missingSignals++;
            }
            if (!entry.hasCondition()) {
                missingCondition++;
            }
            if (!entry.hasMeasuredLatency()) {
                missingMeasuredLatency++;
            }
            if (!entry.hasMeasuredBandwidth()) {
                missingBandwidth++;
            }
            if (entry.scenario() != null) {
                ScenarioCounter counter = counters.computeIfAbsent(
                        entry.scenario(), ScenarioCounter::new);
                counter.sampleCount++;
                if (isEvaluable) {
                    counter.resolvedCount++;
                    if (entry.scenario().equals(entry.resolvedLabel())) {
                        counter.agreementCount++;
                    }
                }
            }
        }

        List<ScenarioOutcome> scenarios = new ArrayList<>();
        for (ScenarioCounter counter : counters.values()) {
            scenarios.add(new ScenarioOutcome(counter.scenario,
                    ResearchValidation.scenarioLabel(counter.scenario),
                    ResearchValidation.scenarioExpectedLabel(counter.scenario),
                    counter.sampleCount, counter.resolvedCount, counter.agreementCount));
        }
        return new Report(registered, evaluable, unreviewed, tied, invalid, agreement,
                missingSignals, missingCondition, missingMeasuredLatency, missingBandwidth, scenarios);
    }

    private static final class ScenarioCounter {
        private final String scenario;
        private int sampleCount;
        private int resolvedCount;
        private int agreementCount;

        private ScenarioCounter(String scenario) {
            this.scenario = scenario;
        }
    }
}
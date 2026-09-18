package com.examora.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure data-quality and matrix aggregation for a research run. Every fact is derived from
 * stored planned/captured samples and human-reviewed labels — nothing is fabricated, and
 * uncollected combinations are never manufactured. Deterministic for unchanged data.
 */
public final class ResearchRunDataQuality {

    private ResearchRunDataQuality() {}

    public record Entry(String sampleId, String runSampleStatus, boolean linkedToResearchSample,
                        boolean reviewed, boolean evaluable, String resolvedLabel, String scenario,
                        boolean invalid, boolean hasSignals, Set<String> unexpectedSignalTypes,
                        boolean measuredLatencyPresent, boolean bandwidthPresent, String conditionsKey) {
    }

    public record Report(int planned, int capturing, int captured, int reviewed, int evaluable,
                         int unreviewed, int tied, int invalid, int missingSignals,
                         int unexpectedSignals, int scenarioGroundTruthAgreement,
                         int scenarioGroundTruthDisagreement, int missingMeasuredLatency,
                         int missingBandwidth) {
    }

    public record MatrixRow(String scenario, int planned, int capturing, int captured,
                            int reviewed, int evaluable, List<String> conditionsKey) {
    }

    public static Report compute(List<Entry> entries) {
        int planned = 0;
        int capturing = 0;
        int captured = 0;
        int reviewed = 0;
        int evaluable = 0;
        int unreviewed = 0;
        int tied = 0;
        int invalid = 0;
        int missingSignals = 0;
        int unexpectedSignals = 0;
        int agreement = 0;
        int disagreement = 0;
        int missingLatency = 0;
        int missingBandwidth = 0;
        if (entries != null) {
            for (Entry entry : entries) {
                switch (entry.runSampleStatus()) {
                    case "PLANNED" -> planned++;
                    case "CAPTURING" -> capturing++;
                    case "CAPTURED" -> captured++;
                    default -> {
                    }
                }
                if (!"CAPTURED".equals(entry.runSampleStatus())) {
                    continue;
                }
                if (!entry.measuredLatencyPresent()) {
                    missingLatency++;
                }
                if (!entry.bandwidthPresent()) {
                    missingBandwidth++;
                }
                if (entry.invalid()) {
                    invalid++;
                }
                if (entry.hasSignals()) {
                    if (!entry.unexpectedSignalTypes().isEmpty()) {
                        unexpectedSignals++;
                    }
                } else if (entry.linkedToResearchSample()) {
                    missingSignals++;
                }
                if (entry.reviewed()) {
                    reviewed++;
                    if (entry.resolvedLabel() == null) {
                        tied++;
                    } else if (!entry.invalid()) {
                        evaluable++;
                        if (entry.scenario() != null) {
                            if (entry.scenario().equals(entry.resolvedLabel())) {
                                agreement++;
                            } else {
                                disagreement++;
                            }
                        }
                    }
                } else {
                    unreviewed++;
                }
            }
        }
        return new Report(planned, capturing, captured, reviewed, evaluable, unreviewed, tied,
                invalid, missingSignals, unexpectedSignals, agreement, disagreement,
                missingLatency, missingBandwidth);
    }

    /**
     * Deterministic experiment-matrix summary grouped by scenario (in fixed display order).
     * Only actual collected samples are reflected; missing combinations are simply absent.
     */
    public static List<MatrixRow> matrix(List<Entry> entries) {
        Map<String, EntryAccumulator> byScenario = new LinkedHashMap<>();
        for (String scenario : ResearchConfig.SCENARIO_ORDER) {
            byScenario.put(scenario, new EntryAccumulator());
        }
        if (entries != null) {
            for (Entry entry : entries) {
                EntryAccumulator acc = byScenario.get(entry.scenario());
                if (acc == null) {
                    acc = byScenario.computeIfAbsent(entry.scenario(), key -> new EntryAccumulator());
                }
                acc.planned += "PLANNED".equals(entry.runSampleStatus()) ? 1 : 0;
                acc.capturing += "CAPTURING".equals(entry.runSampleStatus()) ? 1 : 0;
                acc.captured += "CAPTURED".equals(entry.runSampleStatus()) ? 1 : 0;
                acc.reviewed += entry.reviewed() ? 1 : 0;
                acc.evaluable += entry.evaluable() ? 1 : 0;
                if (entry.conditionsKey() != null && !acc.conditions.contains(entry.conditionsKey())) {
                    acc.conditions.add(entry.conditionsKey());
                }
            }
        }
        List<MatrixRow> rows = new ArrayList<>();
        for (Map.Entry<String, EntryAccumulator> entry : byScenario.entrySet()) {
            EntryAccumulator acc = entry.getValue();
            rows.add(new MatrixRow(entry.getKey(), acc.planned, acc.capturing, acc.captured,
                    acc.reviewed, acc.evaluable, acc.conditions));
        }
        return rows;
    }

    private static final class EntryAccumulator {
        private int planned;
        private int capturing;
        private int captured;
        private int reviewed;
        private int evaluable;
        private final List<String> conditions = new ArrayList<>();
    }
}
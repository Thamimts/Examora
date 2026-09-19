package com.examora.service;

import java.util.ArrayList;
import java.util.List;

public final class ResearchMetrics {

    private ResearchMetrics() {}

    public record Confusion(long truePositives, long trueNegatives, long falsePositives, long falseNegatives) {

        public static final Confusion EMPTY = new Confusion(0, 0, 0, 0);

        public Confusion accumulate(boolean predictedPositive, boolean actualPositive) {
            if (actualPositive && predictedPositive) {
                return new Confusion(truePositives + 1, trueNegatives, falsePositives, falseNegatives);
            }
            if (actualPositive) {
                return new Confusion(truePositives, trueNegatives, falsePositives, falseNegatives + 1);
            }
            if (predictedPositive) {
                return new Confusion(truePositives, trueNegatives, falsePositives + 1, falseNegatives);
            }
            return new Confusion(truePositives, trueNegatives + 1, falsePositives, falseNegatives);
        }

        public long total() {
            return truePositives + trueNegatives + falsePositives + falseNegatives;
        }
    }

    public record EvaluatorMetrics(Double precision, Double recall, Double specificity, Double accuracy,
                                   Double falsePositiveRate, Double falseNegativeRate, Double f1,
                                   Double wrongfulWarningRate, Double fdr) {
    }

    public record LatencyStats(int measuredCount, Double meanMs, Double medianMs, Double minMs, Double maxMs) {

        public static final LatencyStats EMPTY = new LatencyStats(0, null, null, null, null);
    }

    public record BandwidthStats(int measuredCount, Double meanSignalBytes, Double meanRawMediaBytes,
                                 Double meanDataMinimizationRatio) {

        public static final BandwidthStats EMPTY = new BandwidthStats(0, null, null, null);
    }

    public static EvaluatorMetrics compute(Confusion c) {
        long actualPositives = c.truePositives() + c.falseNegatives();
        long actualNegatives = c.trueNegatives() + c.falsePositives();
        long predictedPositives = c.truePositives() + c.falsePositives();
        long total = c.total();

        Double precision = ratio(c.truePositives(), predictedPositives);
        Double recall = ratio(c.truePositives(), actualPositives);
        Double specificity = ratio(c.trueNegatives(), actualNegatives);
        Double falsePositiveRate = ratio(c.falsePositives(), actualNegatives);
        Double falseNegativeRate = ratio(c.falseNegatives(), actualPositives);
        Double accuracy = total == 0 ? null : (double) (c.truePositives() + c.trueNegatives()) / total;
        Double f1 = harmonicMean(precision, recall);
        Double wrongfulWarningRate = ratio(c.falsePositives(), predictedPositives);

        return new EvaluatorMetrics(precision, recall, specificity, accuracy,
                falsePositiveRate, falseNegativeRate, f1, wrongfulWarningRate, wrongfulWarningRate);
    }

    public static LatencyStats latency(List<Long> latenciesMs) {
        if (latenciesMs == null || latenciesMs.isEmpty()) {
            return LatencyStats.EMPTY;
        }
        List<Long> sorted = new ArrayList<>(latenciesMs);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double median = n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return new LatencyStats(n, mean, median, (double) sorted.get(0), (double) sorted.get(n - 1));
    }

    public static BandwidthStats bandwidth(List<long[]> samples) {
        if (samples == null || samples.isEmpty()) {
            return BandwidthStats.EMPTY;
        }
        int measured = 0;
        double signalTotal = 0;
        double rawTotal = 0;
        int ratioCount = 0;
        double ratioTotal = 0;
        for (long[] sample : samples) {
            if (sample == null || sample.length < 2 || sample[0] <= 0) {
                continue;
            }
            measured++;
            signalTotal += sample[1];
            rawTotal += sample[0];
            ratioTotal += 1.0 - ((double) sample[1] / (double) sample[0]);
            ratioCount++;
        }
        if (measured == 0) {
            return BandwidthStats.EMPTY;
        }
        return new BandwidthStats(measured, signalTotal / measured, rawTotal / measured,
                ratioCount == 0 ? null : ratioTotal / ratioCount);
    }

    private static Double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return (double) numerator / (double) denominator;
    }

    private static Double harmonicMean(Double precision, Double recall) {
        if (precision == null || recall == null) {
            return null;
        }
        if (precision == 0.0 || recall == 0.0) {
            return 0.0;
        }
        return 2.0 * precision * recall / (precision + recall);
    }
}
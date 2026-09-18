package com.examora.service;

import java.time.Instant;
import java.util.List;

/**
 * Pure fusion-v1 evaluator: delegates to {@link ProctorFusionService} for the
 * windowed fused score and predicts positive when the score reaches the
 * configured threshold.
 */
public final class FusionEvaluator {

    private final ProctorFusionService fusionService;
    private final double threshold;

    public FusionEvaluator(ProctorFusionService fusionService, double threshold) {
        this.fusionService = fusionService;
        this.threshold = threshold;
    }

    public ProctorFusionService.FusionResult evaluate(List<ProctorFusionService.FusionSignal> windowSignals,
                                                      Instant windowEnd, long windowMs) {
        return fusionService.fuse(windowSignals, windowEnd, windowMs);
    }

    public boolean predict(List<ProctorFusionService.FusionSignal> windowSignals,
                           Instant windowEnd, long windowMs) {
        return evaluate(windowSignals, windowEnd, windowMs).fusedScore() >= threshold;
    }

    public double threshold() {
        return threshold;
    }
}
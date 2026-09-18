package com.examora.service;

import java.util.List;

/**
 * Pure binary baseline evaluator: any in-window evidence counts as a positive
 * prediction. It has no notion of confidence, source, or severity.
 */
public final class BaselineEvaluator {

    public boolean predict(List<ProctorFusionService.FusionSignal> windowSignals) {
        return windowSignals != null && !windowSignals.isEmpty();
    }
}
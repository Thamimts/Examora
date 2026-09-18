package com.examora.service;

import java.util.Set;

public final class ProctorSignalTypes {
    public static final Set<String> FUTURE_AI_SIGNAL_TYPES = Set.of(
            "FACE_COUNT_ANOMALY",
            "PHONE_DETECTED",
            "UNKNOWN_OBJECT",
            "GAZE_ANOMALY",
            "HEAD_POSE_ANOMALY");

    private ProctorSignalTypes() {
    }
}
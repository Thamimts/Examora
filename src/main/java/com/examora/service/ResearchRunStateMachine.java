package com.examora.service;

/**
 * Pure state machine for a research run and its run samples. Codifies which transitions are
 * valid, which are invalid, and which are idempotent, so tests can verify the rules without a
 * database. The service applies these rules and implements each transition with a conditional
 * compare-and-set update so concurrent requests stay deterministic.
 */
public final class ResearchRunStateMachine {

    public static final String PLANNED = "PLANNED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SAMPLE_PLANNED = "PLANNED";
    public static final String SAMPLE_CAPTURING = "CAPTURING";
    public static final String SAMPLE_CAPTURED = "CAPTURED";

    private ResearchRunStateMachine() {}

    public enum RunAction { START, COMPLETE, CANCEL }

    /** Valid run-level transitions; a self-loop marks an idempotent repeat. */
    public static boolean canStart(String status) {
        return PLANNED.equals(status) || RUNNING.equals(status);
    }

    public static boolean canComplete(String status) {
        return RUNNING.equals(status) || COMPLETED.equals(status);
    }

    public static boolean canCancel(String status) {
        return PLANNED.equals(status) || RUNNING.equals(status) || CANCELLED.equals(status);
    }

    /** Runs accept planned samples while planned or running only. */
    public static boolean canCreatePlannedSample(String runStatus) {
        return PLANNED.equals(runStatus) || RUNNING.equals(runStatus);
    }

    /** Captured samples require a running run. */
    public static boolean canCapture(String runStatus) {
        return RUNNING.equals(runStatus);
    }

    /** A run may be completed while any sample is mid-capture (in CAPTURING). */
    public static boolean hasInFlightSample(boolean capturingSamplesPresent) {
        return capturingSamplesPresent;
    }

    /** Run-sample capture transitions from planned or capturing; repeated capture is idempotent. */
    public static boolean canCaptureSample(String sampleStatus) {
        return SAMPLE_PLANNED.equals(sampleStatus) || SAMPLE_CAPTURING.equals(sampleStatus);
    }

    /** Completion of an already completed run is idempotent and deterministic. */
    public static boolean isIdempotentComplete(String status) {
        return COMPLETED.equals(status);
    }

    public static boolean isIdempotentCancel(String status) {
        return CANCELLED.equals(status);
    }

    public static boolean isIdempotentStart(String status) {
        return RUNNING.equals(status);
    }
}
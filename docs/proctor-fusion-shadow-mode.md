# Confidence-Aware Proctoring Fusion — Shadow Mode (fusion-v1)

## Purpose

`ProctorFusionService` combines persisted `proctor_events` into an experimental
"fused evidence score" per exam attempt. It runs **only in shadow / evaluation
mode**: the score is computed, persisted for research and shown in the Command
Center as an *Experimental · Shadow* panel. It has **no effect** on the
deterministic warning, risk, access, termination or retest pipeline, and it
never writes to `exam_attempts` or `exam_access_state`.

## Why shadow mode

Lives are affected by the existing enforcement path (warnings, suspensions,
termination). Any change must be validated against historical and in-flight data
before it may ever influence that path. Shadow mode answers three questions
before any integration is considered:

1. Does the fused score behave sensibly relative to the existing severity-based
   risk score?
2. Is the score stable (deterministic, deduplicated, bounded)?
3. What are its measured precision / recall characteristics on real data?

Nothing here is calibrated for, or claimed to be, a "cheating detector". The
implementation deliberately uses neutral terms such as *fused evidence score*.
The UI never displays "cheating probability" or a "trust score".

## Baseline vs confidence-aware

Two scores are produced on the same evidence window:

- **Baseline score** — a fixed binary trigger (`signal present = 1`, `absent =
  0`) normalised by a documented constant:
  `baselineScore = min(1, evidenceCount / 3)`. Three distinct in-window signals
  saturate the baseline to 1.0. It is a pure research reference, not a model.
- **Confidence-aware (fused) score**:
  `fusedScore = min(1, Σ baseWeight(type) × sourceWeight(source) × confidence)`.

Per-signal confidence resolution:

- When a signal carries a finite confidence in `[0, 1]`, it is used directly.
- AI sources that omit confidence fall back to a conservative documented value
  (`0.7`); AI signals are expected to always carry confidence.
- Non-AI sources without confidence use deterministic source-specific fallbacks
  (BROWSER `0.9`, SYSTEM `0.5`, HUMAN_INVIGILATOR `1.0`, unknown `0.6`).
- Non-finite or malformed confidences resolve to the same fallbacks.

`fusedConfidence` is the mean of the effective per-signal confidences.

## Configuration

All constants live in `service/ProctorFusionConfig.java` (single source of
truth, no scattered magic numbers):

| Component | Value(s) |
| --- | --- |
| Algorithm version | `fusion-v1` |
| Temporal window | 30 000 ms |
| Baseline normaliser | 3 signals → 1.0 |
| Bounded history | 200 rows max per attempt |
| Type base weights | WINDOW_BLUR 0.7, TAB_SWITCH 0.6, CAMERA_OFF 0.8, MULTIPLE_FACES 0.9, AUDIO_DETECTED 0.7, NETWORK_INTERRUPTION 0.4, FULLSCREEN_EXIT 0.8; AI evidence (FACE_COUNT_ANOMALY, PHONE_DETECTED, UNKNOWN_OBJECT, GAZE_ANOMALY, HEAD_POSE_ANOMALY) 0.6–0.8; unknown type 0.5 |
| Source weights | BROWSER 0.8, CLIENT_AI/SERVER_AI/HUMAN_INVIGILATOR 1.0, SYSTEM 0.6; unknown 0.7 |
| Confidence fallbacks | AI `0.7`; BROWSER `0.9`, SYSTEM `0.5`, HUMAN_INVIGILATOR `1.0`, unknown `0.6` |

## Determinism and deduplication

- Only signals whose parsed `occurred_at` lies within
  `[requestTime − window, requestTime]` participate. Malformed timestamps are
  excluded (never evidence).
- Signals are deduplicated by identity (`event_id`, falling back to the row id),
  first-wins **after** sorting by `(occurred_at, id)`, so the result is
  independent of input ordering and repeated delivery/calculation yields the
  identical result.
- All scores are clamped to `[0, 1]`.

## Bounded query-pattern

The per-attempt query filters on `attempt_id + occurred_at` and is served by the
existing `idx_proctor_events_attempt (attempt_id, occurred_at desc)` index,
with a hard `LIMIT` (`MAX_WINDOW_SIGNALS`). There are no unbounded scans and no
N+1 reads: the Command Center enriches each student's fused values from rows
already loaded for the exam; the read endpoint recomputes and idempotently
upserts one row per `(attempt, algorithm_version)`.

## Storage

`proctor_fusion_results` (additive to `schema.sql`) stores the window,
baseline/fused scores, fused confidence, evidence count and algorithm version
for research, keyed by `(attempt_id, algorithm_version)`. Full per-signal
metadata is intentionally **not** duplicated.

## Endpoint and access

`GET /api/proctor/attempts/{attemptId}/fusion` — read-only, teacher/admin only.

- Owner teacher / admin → `200`
- Student → `403`
- Anonymous → `401`
- Teacher of another exam → `403`

The Command Center `shadowFusion` values are served with the existing
`/command-center` payload (same authorization, in-memory computation, no
writes).

## Why fusion cannot enforce (yet)

- Existing behavior must not change: `ProctorFusionService` has no dependency on
  `ProctorEnforcementService`, `ExamAccessRepository` or `ExamAttemptRepository`;
  it cannot warn, suspend, terminate or grant retests.
- The confidence-aware weights are unvalidated hypotheses. Enforcement would
  require the offline evaluation below plus explicit product/ethics sign-off.

## Future validation methodology (for when shadow data is available)

Evaluate `fusion-v1` offline on logged `proctor_fusion_results` + actual outcome
labels (e.g. invigilator-confirmed incidents) with standard metrics:

- precision, recall, F1, specificity, false-positive rate, false-negative rate
- wrongful-warning analysis: track how often a high fused score would have
  triggered a warning that was not warranted
- sensitivity to the window length (`30s` vs `60s` vs `120s`) and to the
  baseline normaliser

Until that evaluation exists, no claim is made that confidence-aware fusion
reduces wrongful warnings or improves detection.
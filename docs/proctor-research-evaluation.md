# Proctor Research Evaluation Framework

The research framework measures, under controlled and human-reviewed conditions, how a
binary proctoring baseline compares with a confidence-aware fusion algorithm. It is an
**experimental evaluation apparatus only**. It never changes enforcement, never warns or
terminates students, and never fabricates data.

## Scope and policy

- **No enforcement side effects.** Creating samples, submitting reviews, and running
  evaluations never reads or mutates warning counts, access state, or attempt status.
  `ProctorResearchIntegrationTest.researchEvaluationDoesNotModifyEnforcementState`
  locks this in.
- **No fabricated results.** Every metric is derived from two inputs only: stored proctor
  signals inside the sample window and human-reviewed ground-truth labels.
- **No automatic data generation.** There is no seeding and no bulk backfill. Researchers
  register samples explicitly, one window at a time.
- **Privacy.** Research never stores raw camera/screen media, biometric embeddings, or
  face-recognition output. Samples carry environment metadata only (lighting,
  camera quality, network, camera angle), byte-count aggregates, and labels. Reviewer
  identity is always server-derived.
- **Neutral terminology.** Labels are descriptive outcomes (`NORMAL`, `NO_ANOMALY`,
  `ANOMALY`, `FACE_COUNT_ANOMALY`, `PHONE_PRESENT`, `MULTIPLE_PERSONS`, …). The system
  does not use `CHEATER`, `CHEATING_PROBABILITY`, or `TRUST_SCORE` anywhere. The dashboard
  labels the tooling as experimental evaluation, never as a verdict on a student.
- **No superiority claim.** The framework compares metrics; it does not establish that
  fusion-v1 is superior to the baseline or that either detector is accurate.

## Entities

- `research_experiments` — a named study with fixed versions:
  - `algorithm_version` defaults to `fusion-v1`
  - `baseline_version` defaults to `baseline-v1`
  - status flow `DRAFT → ACTIVE → COMPLETED → ARCHIVED` (samples stop at COMPLETED/ARCHIVED;
    reviews stop at ARCHIVED).
- `research_samples` — a bounded observation window
  (`start ≤ t ≤ end`, 1–30 s, must not be in the future beyond a grace period) optionally
  tied to an attempt, with curated environment conditions and optional byte counts.
- `research_reviews` — one row per (sample, reviewer). The sample's effective label is the
  majority of its review labels. Split ties are left unresolved and the sample is excluded
  from evaluation until resolved.

## Ground truth

Samples created with or without a label are only **evaluable** once they have at least one
review and a resolved (non-tied) majority label. A provisional label set at sample creation
is informational for reviewers and does not make the sample evaluable.

## Evaluators

- **Binary baseline** (`baseline-v1`): predicts positive when **any** signal falls inside
  the sample window. No confidence, source, or severity weighting.
- **Confidence-aware fusion** (`fusion-v1`): reuses the production shadow fusion formula
  (`ProctorFusionService`) over the same window. Predicts positive when
  `fusedScore ≥ 0.5`.

Both run over signals retrieved by `attempt_id` plus window bounds (widened by 1 s for
clock slack and filtered precisely in the engine), bounded to 200 signals per window, using
indexed lookups (no unbound scans, no N+1 for sample/review counts).

## Metrics

Derived from the confusion matrix:

`precision, recall, specificity, accuracy, falsePositiveRate, falseNegativeRate, f1,
wrongfulWarningRate`

- `wrongfulWarningRate` is **defined as FDR = FP/(FP + TP)**: the fraction of positive
  predictions that were wrong. It is explicitly **not** the false positive rate.
- Undefined metrics (zero denominator) are `null` — never `NaN`/`Infinity`.

## Detection latency

`detectionLatencyMs = first in-window signal occurred_at − windowStart`, computed from
stored signal timestamps (a real measurement, not an estimate). Aggregated as
mean / median / min / max over samples that contain at least one signal.

## Bandwidth and privacy

`dataMinimizationRatio = 1 − signalBytes / rawMediaBytes`, reported only for samples where
raw media bytes were actually measured (`rawMediaBytes > 0`). Byte counts are recorded
aggregates — the framework never transmits or stores raw media merely to measure them, and
never fabricates byte counts.

## Condition stratification

Samples may carry curated environment metadata:

- `lighting`: `NORMAL | LOW | BRIGHT`
- `cameraQuality`: `LOW | MEDIUM | HIGH`
- `network`: `GOOD | DEGRADED`
- `cameraAngle`: `NORMAL | OBSTRUCTED`

Evaluation can group by any single condition key
(`GET …/evaluation?condition=lighting`). No personal demographic attributes are accepted.

## API (admin-only)

All endpoints live under `/api/proctor/research/**`, match the ADMIN role only, and
re-validate the role in the service layer (`STUDENT`/`TEACHER` → 403, anonymous → 401).

| Endpoint | Purpose |
| --- | --- |
| `POST /api/proctor/research/experiments` | create an experiment (defaults `fusion-v1` / `baseline-v1`) |
| `GET /api/proctor/research/experiments` | list experiments |
| `GET /api/proctor/research/experiments/{id}` | experiment detail |
| `POST /api/proctor/research/experiments/{id}/status` | transition status |
| `POST /api/proctor/research/experiments/{id}/samples` | register an observation window |
| `POST /api/proctor/research/samples/{id}/review` | submit a human review |
| `GET /api/proctor/research/experiments/{id}/evaluation` | baseline-vs-fusion evaluation (optionally condition-stratified) |

## Versioning

Every experiment pins its versions at creation and every evaluation result is stamped with
both. If the fusion formula changes, a new experiment must use a new algorithm version
(e.g. `fusion-v2`); historical results are never silently reinterpreted.

## Limitations

- Metrics reflect only curated, consented windows — they do not generalise to deployment.
- Tie-breaks are deliberately conservative (exclude until resolved).
- Latency measures in-window storage time, not streaming/network latency.
- Bandwidth numbers rely on the researcher recording real aggregate byte counts.
- The framework does not claim accuracy for, or superiority of, any detector.
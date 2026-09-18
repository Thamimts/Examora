# Proctor Controlled Research Dataset & Experiment Runner

The controlled research dataset turns the proctoring research framework into a reproducible
experiment runner: an administrator registers a bounded study (a *dataset*), stages samples
that describe an intended, consented proctoring scenario, human reviewers resolve ground
truth, and a single assessment run reports the data-quality accounting plus baseline-v1 vs
fusion-v1 metrics — all stamped with dataset and algorithm versions.

> **This framework does not establish generalization or real-world accuracy by itself.
> Results are limited to the collected controlled dataset.**

## DO-NOT list (hard constraints)

- Never modify enforcement state: `warning_count`, access state, attempt status,
  `terminated_at`, or `terminated_reason`. `researchEvaluationDoesNotModifyEnforcementState`
  locks this in and is isolated from the runner.
- Never add phone / gaze / head-pose detectors, new AI models, face recognition, or
  demographic attributes.
- Never fabricate or bulk-seed research data. Samples are registered explicitly.
- Never transmit or store raw camera / microphone / screen media, biometric embeddings, or
  face-recognition output. Only aggregate byte counts and environment metadata are stored.
- Never accept `studentId`, `examId`, or `reviewerId` from a client — reviewer identity is
  always server-derived from the authenticated ADMIN token.
- Never silently discard invalid research data; it is counted and reported.

## Scenarios (controlled dataset)

Every sample may declare one scenario — the **intended condition of the window**. A scenario
is experiment metadata and **never** a substitute for human review; the resolved human label
remains ground truth. The expected label of a scenario is its own name (e.g. a `TAB_SWITCH`
window is expected to resolve to label `TAB_SWITCH`).

Allow-list (fixed, no arbitrary strings):

`NORMAL, WINDOW_BLUR, TAB_SWITCH, CAMERA_OFF, MULTIPLE_FACES, AUDIO_DETECTED,
FULLSCREEN_EXIT, NETWORK_INTERRUPTION`

`NETWORK_INTERRUPTION` is intentionally a valid research scenario even though it is excluded
from the production three-warning enforcement rule; research and enforcement stay separate.

## Sample lifecycle

A research sample is a bounded window (`1–30 s`, validated duration and clock bounds) with:

- optional `attemptId` — validation requires the window to overlap the attempt span
  (`±60 s`), and the window must not give up a duplicate/overlapping window
- `startedAt` / `endedAt` (aliases for `windowStart` / `windowEnd`)
- `scenario` (allow-listed above, optional)
- environment conditions (lighting, camera quality, network, camera angle)
- optional `measuredLatencyMs` — the **researcher's** measurement, distinct from the
  computed in-window signal latency reported by the engine
- optional aggregate byte counts (`rawMediaBytes`, `signalBytes`) for bandwidth reporting

New windows are rejected (`409`) when they would overlap an existing sample of the same
experiment and attempt context, and when the experiment is `COMPLETED`/`ARCHIVED`.

Students never see this data. Endpoints are ADMIN-only and service-revalidated.

## Ground truth

Evaluable ⇔ (≥ 1 human review) AND (resolved strict-majority label) AND (not invalid).
Split ties stay unresolved and are excluded from metric computation; a provisional label set
at creation is informational only. Scenario never overrides a human label.

## Data quality accounting

Per experiment, every registered sample is counted — never discarded:

- `registered`, `evaluable`, `unreviewed`, `tied`, `invalid`
- `scenarioGroundTruthAgreement` — evaluable samples whose resolved label equals their scenario
- `missingSignals` (attempt-linked samples with no stored in-window signals),
  `missingCondition`, `missingMeasuredLatency`, `missingBandwidth`
- per-scenario outcomes: `scenario`, `label`, `expectedLabel`, `sampleCount`,
  `resolvedCount`, `agreementCount`

## Assessment runs

An assessment run is read-only and deterministic for unchanged data:

- `GET /api/proctor/research/experiments/{id}/evaluate` (or `POST`) — full run
- `GET …/evaluation` — same payload, for display; optionally `?condition=lighting` for
  the condition-breakdown tables

Every result is stamped with `datasetVersion` (default `dataset-v1`), `algorithmVersion`,
`baselineVersion`, `evaluatedAt`, and the evaluated sample count. Metrics never contain
`NaN`/`Infinity` — zero-denominator metrics are `null`. `wrongfulWarningRate` is defined as
strictly FDR = FP/(FP+TP).

Evidence always comes from stored `proctor_events` (bounded to 200 signals per window, ±1 s
slack, filtered precisely in the engine); signals are never copied into research tables.

## Measured vs computed latency

- `measuredLatencyMs` — optional researcher-supplied value (non-negative) captured at
  sample registration and reported in data-quality accounting.
- Computed latency — `first in-window signal − windowStart` from stored signal timestamps,
  aggregated as mean/median/min/max over evaluable samples with signals.

## Privacy

Samples carry environment conditions, byte-count aggregates, scenario, and labels only —
no demographic attributes, no raw media, no face/biometric data. The sample DTO exposes no
`studentId`, `examId`, or `reviewerId`; client-supplied values for those are ignored.

## Versioning

- `dataset-v1` — default research dataset version, stored on each experiment and stamped
  on every run.
- `baseline-v1` — binary baseline (positive if any in-window signal).
- `fusion-v1` — confidence-aware fusion reusing the production shadow formula
  (`fusedScore ≥ 0.5`).

If the detector formula changes, a new experiment pins a new algorithm version; historical
results are never silently reinterpreted.

## API (admin-only)

| Endpoint | Purpose |
| --- | --- |
| `POST /api/proctor/research/experiments` | create experiment (defaults `dataset-v1`, `fusion-v1`, `baseline-v1`) |
| `GET /api/proctor/research/experiments` | list experiments |
| `GET /api/proctor/research/experiments/{id}` | experiment detail |
| `POST /api/proctor/research/experiments/{id}/status` | transition `DRAFT/ACTIVE/COMPLETED/ARCHIVED` |
| `POST /api/proctor/research/experiments/{id}/samples` | register a window (attemptId, startedAt/endedAt, scenario, conditions, measuredLatencyMs, byte counts) |
| `POST /api/proctor/research/samples/{id}/review` | submit a human review |
| `GET /api/proctor/research/experiments/{id}/evaluation` | evaluate (optionally `?condition=`) |
| `GET/POST /api/proctor/research/experiments/{id}/evaluate` | run/refresh the assessment |

## Running a controlled study

1. Create an experiment (name, optional description; dataset/algorithm versions default).
2. Register samples across the scenarios you intend to measure, one window at a time, with
   conditions and optional measured latency/byte counts.
3. Have ≥ 1 reviewer (ideally several) review each sample to a resolved majority.
4. Run `POST …/evaluate`. Read `dataQuality` first — an experiment with many
   `unreviewed`, `tied`, `missing*` or `invalid` samples is not ready to interpret.
5. Only then compare `baseline-v1` against `fusion-v1` on the evaluable set, and treat
   both strictly as measurements of this dataset, not of deployment.

## Limitations

- Results are limited to the collected controlled dataset; they do not generalize.
- Scenario agreement is a descriptor of dataset fidelity, not a score of detector accuracy.
- Computed latency measures in-window storage time, not streaming/network latency.
- Bandwidth and measured latency rely on researchers recording real aggregate values.
# Proctor Research Failure-Case Analysis

The failure-case analysis layer (Milestone 6) is a **pure, deterministic, read-only**
analytical view over the persisted controlled research dataset. It describes the cases where
the binary baseline or the confidence-aware fusion predicted differently from the resolved
human-review ground truth — and nothing else. It never generates, simulates, or invents
observations, never performs statistical inference, and never exports raw media or student
identity.

## Design principles

- **Analysis is a snapshot, not a process.** The endpoints run no task runs, no background
  jobs, and no writes. Repeated calls over unchanged data return byte-identical payloads
  except the `generatedAt` stamp
  (`ResearchFailureAnalysisIntegrationTest.failureSamplesAreDeterministicallyOrderedBySampleId`).
- **Only persisted, evaluable samples.** Samples enter evaluable scope only when they were
  captured, have at least one human review with a **resolved** (non-tied, non-invalid)
  majority label, and pass the same data-quality filter used by statistical analysis. A
  failure case is such a sample where `baselinePositive != groundTruthPositive` or
  `fusionPositive != groundTruthPositive` (or, for disagreements only, where the detectors
  disagreed with each other).
- **Never fabricate.** Empty or partial scopes report `0`, `null`, or empty lists — never a
  simulated value. Confidence bands, durations, and signal-profile statistics are computed
  only from values that were actually measured (`ResearchFailureAnalysisTest` pins the pure
  cases).
- **No causality, no inference.** No p-values, significance, effect-size, or causal claims
  are produced. Observed associations in the controlled dataset must not be interpreted as
  causal relationships.
- **No enforcement, no checks.** Analysis never reads or writes warning counts, access
  state, attempt status, termination state, or retest requests
  (`ResearchFailureAnalysisIntegrationTest.failureAnalysisDoesNotTouchEnforcementState`),
  and never persists anything (`…failureAnalysisPersistsNothingAndLeavesFusionAndBaselineUntouched`).
- **No superiority claim.** Failure categories and confusion counts are reported
  per-evaluator; the report never labels an evaluator better or worse.
- **Privacy by schema.** As in data collection, research tables hold no raw media,
  biometrics, or demographics, and the failure payload carries no student identifiers,
  identity, or raw media — only signal metadata and ranges
  (`…failurePayloadContainsNoStudentIdentityRawMediaOrDemographics`,
  `…researchFailureTablesPersistNoRawMediaNoBiometricsNoDemographics`).
- **Stable ordering.** Categories, transitions, bands, distributions, and strata always
  follow fixed orders; sample-level failure-case lists are ordered by research sample id so
  pagination is stable and deterministic.

## Scope

Two scopes are exposed and both require ADMIN (anonymous 401, student/teacher 403):

- **Whole experiment** (default): analyses samples from all runs with status `COMPLETED`
  or `RUNNING`.
- **Single run** (`?runId=`): analyses that run's captured samples only. The run must
  belong to the experiment (else 400); unknown runs/experiments are 404.

Endpoints:

- `GET /api/proctor/research/experiments/{experimentId}/failure-analysis?runId=`
- `GET /api/proctor/research/experiments/{experimentId}/failure-analysis/samples?runId=&page=&pageSize=`

## What the report contains

The report is an aggregate JSON document carrying the same dataset/baseline/fusion/analysis
version stamps as the statistical report plus `failureAnalysisVersion = failure-analysis-v1`.

| Section | Contents |
| --- | --- |
| Versions | `datasetVersion`, `baselineVersion`, `fusionVersion`, `analysisVersion`, `failureAnalysisVersion`, `generatedAt` |
| Error overview | `evaluableSampleCount`, `failureCaseCount`, `disagreementCount`, `changedPredictionCount`, per-category counts (`BASELINE_FALSE_POSITIVE`, `BASELINE_FALSE_NEGATIVE`, `FUSION_FALSE_POSITIVE`, `FUSION_FALSE_NEGATIVE`, `BASELINE_ONLY_POSITIVE`, `FUSION_ONLY_POSITIVE`), and text `patterns` derived from the observed data |
| Disagreements | baseline/fusion disagreement categories (`BASELINE_ONLY_POSITIVE`, `FUSION_ONLY_POSITIVE`) and transition categories (`CORRECT_TO_CORRECT`, `CORRECT_TO_WRONG`, `WRONG_TO_CORRECT`, `WRONG_TO_WRONG`) with counts; `FUSION_ONLY_POSITIVE` is impossible for the current engine and is never emitted |
| Error transitions | changed-prediction count, correct→wrong and wrong→correct counts plus their fractions, and distributions by scenario, controlled condition (`key=value`), signal source, and AI confidence band |
| Failure by scenario | per-scenario FP/FN counts and rates + `smallSample` flag (n < 5, so descriptive only) |
| Failure by environment | per controlled condition value (lighting, camera quality, network, camera angle) FP/FN counts and rates + `smallSample`, plus `missingConditionCount` |
| Signal profile | per category × source: signal count, sample count, confidence descriptives, duration descriptives; plus `missingConfidenceSignalCount` |
| Confidence bands | failure cases grouped by the sample's max AI (CLIENT_AI/SERVER_AI) confidence into 5 fixed bands (0.00–0.19 … 0.80–1.00); a sample belongs to exactly one band; per band: distinct sample count, signal count, TP/TN/FP/FN, precision, recall, FPR, FNR, FDR |
| Signal association | error-sample count, error samples that carried no signals, and per-source error counts |
| Failure samples | `FailureSamplesPage` — bounded pagination (`page` ≥ 0, `pageSize` clamped 1..100, default 25), ordered by sample id, each case exposing only scenario, ground-truth, both predictions, transition type, condition keys, signal type/source lists, and confidence/duration **ranges** — never the underlying raw data |

All numeric values are never `NaN` or infinite; unavailable values are `null`
(`ResearchFailureAnalysisTest.zeroDenominatorsAreNullNeverNaNOrInfinity`).

## Small-sample handling

Any group with `0 < n < 5` sets `smallSample = true`. The DTO carries the flag, the UI
renders an n < 5 badge, and counts/rates remain strictly descriptive of those observations —
they are never presented as characteristic of the system.

## Implementation

- `ResearchFailureAnalysis` — final pure class, no dependencies, no AI. `analyze(samples,
  datasetVersion, baselineVersion, fusionVersion)` derives baseline/fusion predictions from
  the stored evaluable samples and maps everything to the deterministic `FailureReport`
  record. Constants: `SMALL_SAMPLE_THRESHOLD = 5`, `DEFAULT_PAGE_SIZE = 25`,
  `MAX_PAGE_SIZE = 100`.
- `ResearchAnalysisService.loadScope` — shared scope loader (captured samples, data quality,
  versions, embedded observations) reused unchanged by the statistical report, so both layers
  see the identical evaluable dataset. Re-validates ADMIN.
- `ResearchFailureAnalysisService` — resolves scope, runs the pure analyzer, clamps
  pagination, and maps to DTOs.
- `ResearchController` — the two failure-analysis endpoints above.

## Reliability of assumptions

Baseline and fusion predictions are the same ones already reported by run evaluation
(Milestone 4) — `BaselineEvaluator.predict` and the fusion evaluator (`fusedScore ≥ 0.5`)
are reused. No proctoring, enforcement, baseline, fusion, statistical-analysis, or schema
code was modified; the analysis adds no new stored columns.

## Tests

- `ResearchFailureAnalysisTest` (20, pure): determinism, empty dataset stability, all-correct
  scopes, per-category counting, transition classification (correct→wrong / wrong→correct),
  disagreement identification, scenario/environment grouping in fixed orders, signal
  association (including no-signal errors and per-source counts), signal profiles, confidence
  bands with the one-band-per-sample invariant, zero-denominator nulls, small-sample flags,
  pattern generation, and rejection of fabricated/absent signals.
- `ResearchFailureAnalysisIntegrationTest` (16, end-to-end): authorization (admin student/teacher/anonymous),
  scope errors (404/400), empty-experiment validity, real experiment-wide failure counts +
  transitions, real false-positive dataset, real disagreement dataset, run scoping, bounded
  pagination (default 25, cap 100, deterministic ordering), enforcement isolation,
  non-persistence, payload/schema privacy, and version stamps incl. `failure-analysis-v1`.

## Limitations

- Descriptive of one controlled, consented dataset only — no generalisation and no claim
  beyond the observed scope.
- Small groups (n < 5) are flagged and only descriptive; no inference is attempted.
- Failure cases are defined against the resolved human-review ground truth; reviewer
  disagreement can therefore surface as prediction "errors" that reviewers themselves might
  view differently.
- Confidence bands depend on signals that actually carried a confidence score; signals
  without confidence are counted separately, never invented.
- No demographic, biometric, or raw-media dimension exists, so none is analysed or exported.
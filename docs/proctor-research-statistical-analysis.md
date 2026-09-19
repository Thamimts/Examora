# Proctor Research Statistical Analysis

The statistical analysis layer (Milestone 5) is a **pure, deterministic, read-only** view
over the persisted controlled research dataset. It reports descriptive statistics the way
they actually are in the captured, human-reviewed data — it never generates, simulates, or
invents observations, never claims superiority, and never exports raw media or student
identity.

## Design principles

- **Analysis is a snapshot, not a process.** The endpoint performs no task runs, no
  background jobs, and no writes. Repeated calls over unchanged data return byte-identical
  reports except the `generatedAt` stamp (locked in by
  `ResearchAnalysisIntegrationTest.analysisIsReproducibleExceptGeneratedAt`).
- **Only persisted, evaluable samples.** Samples enter evaluable scope only when they were
  captured, have at least one human review with a **resolved** (non-tied, non-invalid)
  majority label. Unreviewed, tied, and invalid samples are counted in data-quality but
  contribute nothing to metrics, strata, deltas, or agreement.
- **Never fabricate.** Empty or partial scopes report `0`, `null`, or empty lists — never a
  simulated value. `ResearchStatisticalAnalysisTest.emptyDatasetProducesEmptyReport…` and
  `…bandwidthIsUnavailableWhenNotMeasured` pin this down.
- **No enforcement, no checks.** Analysis never reads or writes warning counts, access
  state, attempt status, or termination state
  (`ResearchAnalysisIntegrationTest.analysisDoesNotTouchEnforcementState`), and never
  persists anything (`…analysisPersistsNothing…`).
- **No superiority claim.** Metric deltas are reported as observed differences (fusion minus
  baseline). The report never labels an evaluator better or worse and never generalises
  beyond the controlled dataset.
- **Privacy by schema.** As in data collection, research tables hold no raw media,
  biometrics, or demographics, and the analysis payload carries no student identifiers
  (`ResearchAnalysisIntegrationTest.researchTablesPersist…`,
  `…assertPayloadContainsNoStudentIdentifiersOrDemographics`).

## Scope

Two scopes are exposed and both require ADMIN:

- **Whole experiment** (default): aggregates samples from all runs with status
  `COMPLETED` or `RUNNING`.
- **Single run** (`?runId=`): aggregates that run's captured samples only. The run must
  belong to the experiment (else 400); unknown runs/experiments are 404.

`registeredSamples` is the experiment's total sample count in experiment scope, or the
in-scope captured count in run scope.

## What the report contains

The report is an aggregate JSON document (`AnalysisReportDto` — no sample-level rows).

| Section | Contents |
| --- | --- |
| Versions | `datasetVersion`, `baselineVersion`, `fusionVersion`, `analysisVersion`, `generatedAt` |
| Dataset overview | registered / captured / reviewed / evaluable / unreviewed / tied / invalid / missing-signal / missing-condition / missing-latency / missing-bandwidth counts + `evaluableCoverage` (evaluable/captured, null when captured = 0) |
| Evaluators | baseline-v1 and fusion-v1 confusion matrices and metrics (precision, recall, specificity, accuracy, FPR, FNR, F1, wrongful-warning rate/FDR) |
| Metric deltas | fusion − baseline for each metric; null if either side is null |
| Prediction changes | baseline/fusion correct and error counts, changed-prediction count, baseline-correct→fusion-wrong (regressions) and baseline-wrong→fusion-correct (repairs) |
| Disagreement | per-category counts + percentages (`AGREE_POSITIVE`, `AGREE_NEGATIVE`, `BASELINE_ONLY_POSITIVE`, `FUSION_ONLY_POSITIVE`) and a list of the differing samples (id, scenario, ground truth, both predictions, signal summary — no raw data) |
| Stratification | per-scenario and per-condition metrics+deltas; conditions are the controlled dimensions only (lighting, camera quality, network, camera angle) |
| Confidence | descriptive confidence distribution (min/max/mean/median/count) per signal source |
| Latency | measured latency and stored-window latency (earliest in-window signal − windowStart), each descriptive; plus per-scenario and per-condition latency |
| Bandwidth | raw-media bytes vs stored signal bytes descriptives (0 bytes counted as measured) |
| Review agreement | reviewed/resolved/tied counts, agreement rate, pairwise agreement rate, single-reviewer-dataset flag |
| Confidence intervals | 95% Wilson intervals for precision/recall/specificity/accuracy/FPR/FNR/FDR — **not** F1 (no justified interval), empty when n = 0 |

All numeric values are never `NaN` or infinite; unavailable values are `null`
(`ResearchStatisticalAnalysisTest.zeroDenominatorsAreNullNeverNaNOrInfinity`).

## Implementation

- `ResearchStatisticalAnalysis` — final pure class, no dependencies. `analyze(samples,
  reviews, dataQuality, versions)` maps inputs to the deterministic `Report` record.
- `ResearchAnalysisService` — selects the scope, loads stored samples/signals/reviews,
  derives predictions via the existing `ResearchExperimentRunner.predict` and
  `ProctorFusionService.fuse` over stored in-window signals, computes data-quality counts,
  and maps the report to DTOs. Re-validates ADMIN.
- `ResearchRepository.findReviewsByExperiment` — full review rows for pairwise agreement.
- `ResearchController` — `GET /api/proctor/research/experiments/{experimentId}/analysis?runId=`.

## Reliability of assumptions

`ResearchMetrics.Compute` (F1, FDR, etc.) and `ProctorFusionService.fuse` are reused
unchanged from evaluation — fusion predictions reported here are the same ones already
reported by run evaluation (Milestone 4). No proctoring, enforcement, baseline, or fusion
code was modified.

## Tests

- `ResearchStatisticalAnalysisTest` (26, pure): determinism, empty datasets, perfect/biased
  classifiers, null-vs-NaN, deltas, disagreement categories/samples, stratification order,
  confidence, latency (measured/stored-window/by-scenario/by-condition), bandwidth, review
  agreement, Wilson boundaries, prediction transitions.
- `ResearchAnalysisIntegrationTest` (11, end-to-end): authorization, 404/400 scope errors,
  real experiment-wide counts + confusion + disagreement, run-scoped analysis, empty
  experiment validity, eligible-run exclusion, reproducibility, enforcement isolation,
  non-persistence, schema-level privacy.

## Limitations

- Descriptive of one controlled, consented dataset only — no generalisation.
- Small evaluable counts produce wide Wilson intervals; the UI shows the observed counts.
- Stored-window latency measures in-window storage time, not network/streaming latency.
- No demographic or biometric dimension is analysed (none is collected).
- Confidence values come only from signals that actually carried a confidence score.
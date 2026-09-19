# First controlled experiment (milestone 4)

This document pins the first controlled research experiment on top of the milestone-3
run/sample/review framework (`docs/proctor-controlled-experiments.md` and
`docs/proctor-research-data-collection.md`). It adds a **run-scoped evaluation** that
reports collection progress against a target and evaluates the captured run samples on
the same baseline-v1 / fusion-v1 engines as the experiment-level assessment.

## Target is informational, never a gate

- `ResearchConfig.TARGET_SAMPLES_PER_SCENARIO = 10`, `TARGET_EXPERIMENT_SAMPLES = 80`
  (8 scenarios × 10).
- The target appears in the run evaluation UI as progress (`captured / target`) and in the
  completion summary. Meeting the target is **not** required to call the evaluation read
  endpoint and it never gates review, capture, or reporting. Ties and unreviewed samples
  are simply excluded and counted.

## Controlled condition order

`lighting`, `cameraQuality`, `network`, `cameraAngle` (`ResearchConfig.CONTROLLED_CONDITION_ORDER`).
The condition distribution always reflects only values actually present on captured
run samples — empty dimensions render as empty, not as zero-filled guesses.

## Run evaluation endpoint

`GET /api/proctor/research/runs/{runId}/evaluation` (ADMIN only, consistent with the rest
of the research API). It is read-only and deterministic for unchanged data; only
`snapshot.evaluatedAt` changes between identical calls.

Response shape (`RunEvaluationDto`):

| Field | Meaning |
| --- | --- |
| `progress` | target / planned / capturing / captured / reviewed / evaluable |
| `scenarioProgress` | per-scenario target + planned/captured/reviewed/evaluable |
| `conditionDistribution` | per condition key, observed value counts |
| `dataQuality` | run data-quality accounting (unreviewed, tied, invalid, missing…) |
| `baseline` / `fusion` | evaluator results on the same evaluable set (incl. `fdr`) |
| `reviewAgreement` | per linked sample: review count, resolved label, agreement state |
| `disagreements` | contexts where baseline and fusion predict different classes |
| `conditions` | per (condition key, value) group: counts + baseline/fusion results |
| `latency` / `bandwidth` | computed from stored signal metadata (`measured` counts, mean/median/min/max) |
| `completion` | target observations, captured/reviewed/evaluable, scenario & condition cell coverage, `readyForEvaluation` |
| `snapshot` | dataset / baseline / fusion versions, `evaluatedAt`, evaluable sample count |

## Semantics (invariants)

- **Evaluable** = captured + reviewed + reviews resolve (logsheet label) + not invalid.
- **TIED** (no strict majority) and **UNREVIEWED** samples are never evaluated; they are
  reported separately in `dataQuality` and the run review agreement.
- **Zero denominators** render as `null` (`fdr`, precision, recall, …) — never `NaN` or
  `Infinity`. The DTO is tested to be free of non-finite numbers.
- **FDR** (false discovery rate) = FP / (TP + FP), aliased from `wrongfulWarningRate`.
  It is reported per evaluator on the run and per condition-value group.
- **Disagreement** = a captured sample evaluated by both engines whose binary predictions
  differ (e.g. baseline-v1 positive, fusion-v1 negative for a single low-confidence
  `TAB_SWITCH` / `BROWSER` signal). The conditions key, ground-truth label, signal type/
  source counts and confidence/duration are attached so the discrepancy is inspectable.
- **Scenario intent stays metadata**: the declared scenario is never used as automatic
  ground truth; only majority human-review labels are.
- Capture timing is server-authoritative (observe stamps `started_at`); clients may only
  supply `endedAt` and aggregate byte counts.
- The run evaluation is **served from stored state**, no synthetic data is inserted, and
  the run lifecycle / evaluation never mutates enforcement state.

## Ordering and determinism

Captured contexts are sorted by (scenario index, run sample id) so two evaluations of the
same data return identical arrays. `ResearchRunEvaluationIntegrationTest` re-evaluates a
run after a delay and asserts equality after stripping `evaluatedAt`.

## Collection checklist (run evaluation UI)

1. Capture target samples per scenario (progress bar per scenario).
2. Review every captured sample.
3. Resolve tied reviews via strict-majority human review.
4. Run the evaluation on the evaluable set (`readyForEvaluation`).

## Test coverage (the requested 24 items)

Two new test classes exercise milestone 4:

- `ResearchRunEvaluationTest` (13, pure JUnit): target informational at 80 across 8
  scenarios; agreement-state semantics (UNREVIEWED / TIED / AGREED / RESOLVED); FDR equals
  wrongful warning rate; FDR null when nothing predicted positive; extreme confusion stays
  finite; no NaN/Infinity; determinism; condition groups only contain present values;
  latency mean/median/min/max and empty cases; bandwidth aggregate and empty cases;
  zero-denominator cells are null.
- `ResearchRunEvaluationIntegrationTest` (6, Spring Boot): endpoint auth (401/403 for
  anonymous/student/teacher, allowed for ADMIN); unknown run is 404; an end-to-end run
  (experiment → run → observe → capture → reviews) reporting progress, completion,
  confusion with FDR, disagreements, condition breakdown, latency/bandwidth "measured"
  counts, and a snapshot with no student identifiers/demographics; reproducibility except
  `evaluatedAt`; enforcement state untouched; research tables persist no raw media /
  biometrics / demographics (schema assertion).

## No export endpoint

Run evaluation never exposes raw media — an export endpoint does not exist anywhere in the
codebase, and the privacy test asserts the evaluation payload contains no student
identifiers, no demographics, and no raw media payloads.
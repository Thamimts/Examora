# First controlled research pilot — execution protocol

This document is the operator-facing protocol for executing the first controlled
Examora research pilot. It reuses the milestone-2/4 run, sample, review, and
data-quality terminology (`docs/proctor-research-data-collection.md`, milestone 4 in
`docs/proctor-first-controlled-experiment.md`) and the existing scenario instructions
(`ResearchConfig.SCENARIO_INSTRUCTIONS`). It changes nothing in application behavior,
schema, `baseline-v1`, `fusion-v1`, enforcement, or research analysis code.

## 1. Purpose

The purpose of this pilot is to collect the first controlled observations with the
existing research-run framework: captured, human-reviewed samples across all 8
controlled scenarios, each carrying the 4 controlled condition dimensions and the
measurement counters (`measured_latency_ms`, `raw_media_bytes`, `signal_bytes`).

The informational pilot target is `ResearchConfig.TARGET_EXPERIMENT_SAMPLES = 80`
(8 scenarios × `TARGET_SAMPLES_PER_SCENARIO = 10`). The target is progress reporting
and completion-summary metadata only. **80 is not a statistical sufficiency claim**;
it is an informational planning target that never gates capture, review, reporting, or
evaluation. No statistical power, precision, or significance assertion follows from
reaching it, and no expected outcome is predicted here.

## 2. Experiment setup

The existing experiment (named `fusion comparision`) currently exists in the live
database with status `DRAFT`. The pilot proceeds through the existing experiment and
run endpoints (ADMIN only).

1. **Activate the existing experiment.** Move the experiment from `DRAFT` to `ACTIVE`
   using the existing experiment status transition. An `ARCHIVED` experiment is
   read-only and rejects runs and planned samples; an `ACTIVE` experiment accepts them.
2. **Create a research run** with a unique run code. The run records its own
   `dataset_version` (default `dataset-v1`).
3. **Plan the sample matrix.** A run accepts planned samples while `PLANNED` or
   `RUNNING` only. Each planned sample references an existing exam attempt, a scenario,
   and one value per controlled condition.

### Run lifecycle

The run-level lifecycle is `PLANNED → RUNNING → COMPLETED` (or `CANCELLED`). The
per-sample lifecycle is `PLANNED → CAPTURING → CAPTURED`. The combined operational
sequence is:

```
PLANNED  ──start──►  RUNNING  ──complete──►  COMPLETED
   │                     │                        │
   │ plan samples        │ observe (server-stamps │
   │                     │   started_at)          │
   │                     ▼                        │
   │              PLANNED → CAPTURING ─capture─► CAPTURED
   └─────────────cancel──► CANCELLED (read-only)
```

- **PLANNED** — the run accepts planned samples.
- **RUNNING** — observation and capture are allowed; completion is possible.
- **COMPLETED** — the run is finished and read-only; repeated completion is idempotent.
- **CANCELLED** — the run was aborted and is read-only; cancellation from `PLANNED` or
  `RUNNING` is valid and repeated cancellation is idempotent.

### Existing error / cancellation paths

- Starting a run from an invalid status returns a conflict (`409`) explaining the
  current status.
- **Completion is blocked while any sample is mid-capture** (`CAPTURING`); the caller
  must finish or abandon that capture first.
- Capturing requires a `RUNNING` run and a sample already in `CAPTURING`; a sample must
  be **observed first**, and `started_at` is always server-stamped (never client-supplied).
- Capture validates the window (duration between 1 s and 30 s, end not in the future,
  window overlapping the referenced attempt span), rejects overlapping samples of the
  same experiment/attempt, and rejects duplicate `(run, attempt, scenario, conditions)`.
- Invalid status transitions return `409` and leave persisted state unchanged (all
  transitions are conditional compare-and-set updates).

## 3. Scenario execution

For each scenario below, execute exactly one observation per checklist. The text is
reproduced from `ResearchConfig.SCENARIO_INSTRUCTIONS` — the existing instructions are
not modified. "Expected signals" lists `ResearchConfig.SCENARIO_EXPECTED_SIGNALS`, used
for data-quality reporting only (unexpected-signal detection); it is never a substitute
for human review.

### NORMAL
- **Description:** Student studies and answers normally without any anomaly.
- **Expected action:** No anomalous proctoring title is expected; detection should produce nothing.
- **Duration guidance:** 20-30 seconds of continuous normal activity.
- **Reviewer observation:** Note any noise, screen activity, or camera movement that produced an unexpected signal.
- **Expected signals:** (none)
- **Operator checklist:** confirm the camera is on, lighting is stable, no screen/textbook activity near the lens, and no network dropout during the window. Any signal produced is flagged as unexpected.

### WINDOW_BLUR
- **Description:** The camera is briefly blurred, for example by finger smudging or lens obstruction.
- **Expected action:** A WINDOW_BLUR anomaly should fire while the blur is present.
- **Duration guidance:** 15-25 seconds with blur present.
- **Reviewer observation:** Confirm the blur was incidental and limited to the window, not a deliberate concealment.
- **Expected signals:** `WINDOW_BLUR`
- **Operator checklist:** cause a genuine incidental blur (lens smudge/obstruction) for the guidance duration, keep the rest of the environment unchanged, and note the exact blur interval so the capture window covers it.

### TAB_SWITCH
- **Description:** The student switches to another tab or application and stays there briefly.
- **Expected action:** A TAB_SWITCH anomaly should fire during the switch.
- **Duration guidance:** 10-20 seconds of focus on another tab.
- **Reviewer observation:** Confirm the switch was deliberate and outside the exam context.
- **Expected signals:** `TAB_SWITCH`
- **Operator checklist:** switch tabs/applications for the guidance duration, return to the exam, and capture a window that contains the switch and the return.

### CAMERA_OFF
- **Description:** The student turns the camera off (hardware control or OS permission denial).
- **Expected action:** A CAMERA_OFF anomaly should fire while the camera is disabled.
- **Duration guidance:** 10-20 seconds with the camera disabled.
- **Reviewer observation:** Confirm the lens was reliably disabled; note any mandatory-camera window the app enforced.
- **Expected signals:** `CAMERA_OFF`
- **Operator checklist:** disable the camera for the guidance duration, re-enable it, and ensure the capture window starts after the camera is off and ends after it is restored.

### MULTIPLE_FACES
- **Description:** A second person enters the camera frame while the student remains present.
- **Expected action:** A face-count anomaly should fire (MULTIPLE_FACES, FACE_COUNT_ANOMALY, or MULTIPLE_PERSONS).
- **Duration guidance:** 15-25 seconds with the second face visible.
- **Reviewer observation:** Confirm both faces stayed in frame and the second person was not on-screen media only.
- **Expected signals:** `MULTIPLE_FACES`, `FACE_COUNT_ANOMALY`, `MULTIPLE_PERSONS`
- **Operator checklist:** keep the student's face and a second real person's face in frame for the guidance duration; no TVs/screens with faces in the background.

### AUDIO_DETECTED
- **Description:** Unexpected audio (speech, music, or alert) is played from a device near the student.
- **Expected action:** An AUDIO_DETECTED anomaly should fire while the audio plays.
- **Duration guidance:** 15-25 seconds of continuous unexpected audio.
- **Reviewer observation:** Confirm the audio originated from the testing environment.
- **Expected signals:** `AUDIO_DETECTED`
- **Operator checklist:** play continuous unexpected audio near the device for the guidance duration and record the audio interval within the window.

### FULLSCREEN_EXIT
- **Description:** The student exits fullscreen mode and stays in a windowed browser layout.
- **Expected action:** A FULLSCREEN_EXIT anomaly should fire after leaving fullscreen.
- **Duration guidance:** 10-20 seconds outside fullscreen.
- **Reviewer observation:** Confirm fullscreen was left intentionally and the exam window remained visible.
- **Expected signals:** `FULLSCREEN_EXIT`
- **Operator checklist:** leave fullscreen for the guidance duration while the exam window stays visible, then restore fullscreen before the window ends.

### NETWORK_INTERRUPTION
- **Description:** Network connectivity drops or becomes unusable for a short interval.
- **Expected action:** A NETWORK_INTERRUPTION anomaly should fire while connectivity is lost.
- **Duration guidance:** 15-30 seconds of failed connectivity.
- **Reviewer observation:** Confirm the disconnection was genuine; note that this scenario is excluded from the production three-warning enforcement rule.
- **Expected signals:** `NETWORK_INTERRUPTION`
- **Operator checklist:** suspend network connectivity for the guidance duration, capture a window covering the offline interval and the recovery, and confirm events persisted on reconnect. The scenario is valid research metadata despite being excluded from production enforcement.

## 4. Controlled conditions

Every planned (and therefore every captured) sample must record exactly the four
controlled condition keys with one value each from the existing vocabulary
(`ResearchConfig.CONTROLLED_CONDITIONS`):

| Key            | Allowed values                |
|----------------|-------------------------------|
| `lighting`     | `GOOD`, `LOW`                 |
| `cameraQuality`| `HD`, `LOW`                   |
| `network`      | `STABLE`, `INTERRUPTED`       |
| `cameraAngle`  | `FRONT`, `OFF_ANGLE`          |

These are stored server-side in `research_run_samples.conditions_json` (and copied to the
linked `research_samples.metadata`); a canonical `conditions_key`
(`key=value;...`, keys sorted) is derived for the uniqueness constraint and reporting.

`conditions_json`/`metadata` is **required** for downstream analysis: the statistical and
run evaluations read the value per condition key from stored conditions to compute the
per-dimension condition distribution and per-condition-value baseline-v1 / fusion-v1
groups. Samples whose conditions are incomplete are counted as missing-condition and are
excluded from per-dimension condition stratification. Unknown keys or values are rejected
up-front by the existing `validateControlledConditions` validation, so a demographic
attribute is structurally impossible to record.

## 5. Measurement capture

Capture is complete only when the existing capture validation accepts the request. Every
valid capture carries:

- **`measured_latency_ms`** — end-to-end observation latency, non-negative.
- **`raw_media_bytes`** — the raw-media byte counter for the window.
- **`signal_bytes`** — the signal-byte counter for the window.

`raw_media_bytes`/`signal_bytes` are **size counters only**; no raw media payloads are
stored, and the existing `validateByteCounts` requires them to be provided together or
both absent. Latency and bandwidth presence is tracked in run data quality
(`missingMeasuredLatency`, `missingBandwidth`); a sample missing either remains captured
but is reported as incomplete and contributes no latency/bandwidth measurements.

## 6. Human review

After capture, every sample is reviewed through the existing review workflow on the
linked `research_samples` row.

- **Two independent reviewers per sample**, ideally drawn from different operators, each
  recording their own label (and optional confidence/notes) without seeing the other's
  review.
- **Reviewers must NOT treat scenario intent as ground truth.** The declared scenario is
  experiment metadata used only for planning and quality reporting; the ground-truth label
  is the resolved human-review majority. Agreement between the scenario and the resolved
  label is reported as a quality metric, never fed into labelling.
- **Resolution follows the existing rules** (`ResearchValidation.majorityLabel`,
  `agreementState`): a single review is a unanimous `AGREED`; multiple reviews with a
  strict majority resolve as `AGREED` (unanimous) or `RESOLVED` (non-unanimous majority);
  no strict majority is a **tie**.
- **TIED** samples have reviews but no resolved majority. They are **never evaluated and
  never silently converted** — they are counted and reported, and the operators must add
  or fix reviews to break the tie if the sample is meant to be evaluable.
- **UNREVIEWED** samples have no reviews and are excluded from evaluation.
- **Invalid samples** (see `ResearchSampleValidity`: window outside the attempt's span, or
  a window overlapping another sample of the same experiment/attempt) are excluded from
  evaluation and reported under `invalid`. Do not include invalid samples in the run.

## 7. Data-quality checklist

Before completing a run, verify against the run detail / run-evaluation data-quality
report:

- **All 8 scenarios represented**: one or more PLANned→CAPTURED→reviewed samples for
  NORMAL, WINDOW_BLUR, TAB_SWITCH, CAMERA_OFF, MULTIPLE_FACES, AUDIO_DETECTED,
  FULLSCREEN_EXIT, NETWORK_INTERRUPTION.
- **Conditions populated**: the four controlled condition keys are present on every
  captured sample (no missing-condition samples if avoidable).
- **Latency populated**: `measured_latency_ms` present on every capture (no
  `missingMeasuredLatency`).
- **`raw_media_bytes` populated** and **`signal_bytes` populated** together on every
  capture (no `missingBandwidth`).
- **Required signals persisted**: for each scenario the expected signal type(s) appear in
  the window (`hasSignals`, no unexpected signals), and in-window signals are present in
  `proctor_events` so the runner can load them.
- **Reviews completed**: every captured sample has its two independent reviews; no
  `UNREVIEWED` or `TIED` remaining if the run is meant to be evaluated.
- **No invalid samples accidentally included**: `invalid` count is zero.

Only when these hold should the run be **completed** (completion is blocked with 409 while
any sample is still `CAPTURING`). After completion the run is read-only; the experiment
evaluation then includes its captured-and-reviewed samples.

## 8. Pilot sequence

1. **First 8 samples — one per scenario** across the 8 scenarios (covering all
   scenarios, one controlled-condition combination each, all four measurement counters,
   both reviews per sample).
2. **Inspect data quality** after those 8: scenario coverage, conditions, latency,
   bandwidth, signals expected vs unexpected, review completion, and validity (section 7).
   Fix any capture/review gaps before scaling up.
3. **Continue toward the informational target**: 10 samples per scenario (80 total),
   spreading the controlled condition values so each dimension is represented rather than
   relying on a single condition combination.

The first-8 checkpoint is a quality gate on procedure, not on outcome: reaching it does
not make the dataset statistically sufficient (see sections 1 and 9).

## 9. Research integrity

The following facts hold throughout the pilot and its reporting:

- **Scenario intent is not ground truth.** The declared scenario is metadata for planning
  and quality reporting; only majority human-review labels resolve ground truth.
- **AI signals are not ground truth.** In-window proctor signals come from the existing
  detection pipeline; the expected-signal list is a data-quality aid, not a label.
- **AI confidence is evidence metadata.** Signal/fused confidence is recorded and
  reported as context for inspection (min/max/mean, disagreement detail), never used to
  override human review.
- **No enforcement outcome is human-review ground truth.** The research flow never
  touches enforcement state (`warning_count`, attempt status, termination fields, access
  state); no warning or termination result is used as a ground-truth label.
- **Pilot results must not be interpreted as causal evidence.** Observed associations in
  the controlled dataset are descriptive; they imply no causal relationship.
- **Small-sample results must be disclosed.** Every reported metric carries its sample
  count; groups with fewer than the disclosure threshold are identified as small-sample
  and reported with their exact `n`.

This protocol asserts no statistical significance and no expected outcome; it is a
procedure for honest, persisted, reviewable collection only.
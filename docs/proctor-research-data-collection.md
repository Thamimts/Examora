# Proctor research: controlled data collection and experiment runs

This document explains how a researcher runs a controlled proctoring study in Examora.
It is the operational guide that pairs with the `Proctoring research` screen and the
`/api/proctor/research/**` API (administrator only).

## Purpose

The classic research workflow on this platform is *ad hoc*: an administrator manually
registers research samples referencing existing `proctor_events`. Milestone 3 adds the
controlled flow used when a scenario (what the experiment alters) and a controlled
condition (the environment it alters it under) must be planned ahead of time — the
scenario × condition matrix.

A **research run** is a bounded collection session:

- `PLANNED` — the run accepts planned samples.
- `RUNNING` — observation and capture are allowed.
- `COMPLETED` — the run is finished and read-only.
- `CANCELLED` — the run was aborted and is read-only.

## The controlled-condition vocabulary

Planned samples may only use these environment values (this is enforced server-side,
so a demographic field such as gender, race or ethnicity is structurally impossible):

| Key            | Allowed values                | Meaning                          |
|----------------|-------------------------------|----------------------------------|
| `lighting`     | `GOOD`, `LOW`                 | Environment lighting             |
| `cameraQuality`| `HD`, `LOW`                   | Camera quality                   |
| `network`      | `STABLE`, `INTERRUPTED`       | Connectivity reliability         |
| `cameraAngle`  | `FRONT`, `OFF_ANGLE`          | Camera placement                 |

These keys match the stratification keys used by the evaluation engine, so a run's
matched conditions can be compared against the general samples later.

## Scenario instructions

Every scenario carries instructional text used by the researcher when planning a run
(how the participant should behave) and by reviewers (what to look for). It also lists
the signal types that a correctly-executed scenario is *expected* to produce:

| Scenario              | Expected signals                               |
|-----------------------|------------------------------------------------|
| `NORMAL`              | (none)                                         |
| `WINDOW_BLUR`         | `WINDOW_BLUR`                                  |
| `TAB_SWITCH`          | `TAB_SWITCH`                                   |
| `CAMERA_OFF`          | `CAMERA_OFF`                                   |
| `MULTIPLE_FACES`      | `MULTIPLE_FACES`, `FACE_COUNT_ANOMALY`, `MULTIPLE_PERSONS` |
| `AUDIO_DETECTED`      | `AUDIO_DETECTED`                               |
| `FULLSCREEN_EXIT`     | `FULLSCREEN_EXIT`                              |
| `NETWORK_INTERRUPTION`| `NETWORK_INTERRUPTION`                         |

> **The scenario is NOT ground truth.** Scenario intent is researcher metadata used for
> run planning and quality reporting. Agreement between the scenario and the resolved
> human-review label is reported as a quality metric; it never feeds automatic labelling.

## The observation model (honest timing)

A capture requires an *observation window*. To avoid fabricated timestamps:

1. `observe` — the researcher signals that observation has started. The server stamps
   `started_at` (`Instant.now()`); the client cannot supply it.
2. `capture` — the researcher supplies only `endedAt`, plus optional `measuredLatencyMs`,
   `rawMediaBytes`, `signalBytes`. `startedAt` in the request body is ignored.

The window is validated: duration between 1 s and 30 s, end not in the future, and the
window must overlap the referenced attempt's span (60 s tolerance). No two samples in the
same run+attempt may share the window, and no planned/captured sample in the run may
duplicate `(run, attempt, scenario, conditions)`.

A `research_samples` row is created with the same server-stamped window, then linked to
the run sample. Reviews posted against that linked sample become the run's ground truth.

## Workflow (13 steps)

1. As an administrator, create an **experiment**. Optionally bind it to an exam via
   `examId`, so later planned samples can be checked to belong to that exam.
2. On the experiment's **Controlled runs** panel, create a **run** with a unique run
   code.
3. **Start** the run (PLANNED → RUNNING).
4. For each matrix cell, **plan** a sample: choose the scenario and one value per
   controlled condition. The attempt must exist and belong to the experiment's exam.
   Scenario instructions and the condition catalog are available on the same screen.
5. Make sure the attempt's activity is live (events are produced by the existing
   proctor pipeline and remain in `proctor_events`).
6. **Observe** the plan (PLANNED → CAPTURING); the server stamps `started_at`.
7. **Capture** (CAPTURING → CAPTURED) by supplying the end of the observation window.
   The system inserts the linked `research_samples` row and keeps the window metadata.
8. Repeat 6–7 for every cell of the scenario × condition matrix.
9. **Review** each captured research sample; the resolved human-review majority becomes
   that sample's ground truth. A tie leaves the sample unevaluable (never silently
   resolved, never discarded).
10. Open the **run detail**: data-quality counts (planned, capturing, captured,
    reviewed, evaluable, unreviewed, tied, invalid), missing signals, unexpected
    signals, scenario agreement/disagreement, missing latency/bandwidth, and the
    per-scenario matrix.
11. Inspect a captured sample's **detail** if needed: signal types and sources in the
    window, confidence min/max/mean, max signal duration, the baseline and fusion
    predictions and the scenario-agreement flag. It contains no raw media, no
    biometric data and no demographic attributes.
12. **Complete** the run (blocked with 409 while any sample is still CAPTURING) or
    **cancel** it. Repeated completion is idempotent.
13. Evaluate the experiment as usual: captured-and-reviewed samples from the run flow
    into the experiment evaluation, including latency/bandwidth and scenario metrics.

> The run lifecycle, sampling, observing and reviewing **never modify production
> enforcement state**: `warning_count`, attempt status, termination fields and access
> state are untouched. Nothing in the research flow issues warnings or terminates.

## Reproducibility

- Run-specific `dataset_version` (default `dataset-v1`) is recorded on the run and the
  evaluation reports both `algorithmVersion`/`baselineVersion` (currently
  `fusion-v1`/`baseline-v1`) so comparisons can be traced.
- Reviews are stored transactionally with the reviewer; the resolved label is the
  majority and ties are tracked, not dropped.
- All timestamps are server-derived; capture cannot back-date a window.

## Privacy and limitations

- No raw video, audio or image data is stored. `rawMediaBytes`/`signalBytes` are size
  counters only (used for the data-minimization ratio, never payloads).
- No face recognition, biometric identity, or demographic attributes are collected; the
  controlled-condition vocabulary has no such keys and the API rejects unknown keys.
- Planned samples need an existing attempt, so a fully offline ("tabletop") run is not
  supported — the observation pipeline must be live.
- The 1 s minimum window bounds how "sharp" a capture can be; plan 1–3 s windows.
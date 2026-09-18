# Client-Side Face Count Detection — Architecture, Privacy & Research Notes

This is the **first experimental client-side AI detector** in Examora. It counts faces in the
student's camera feed entirely **on the student's device** and submits a small, structured
evidence signal when the observed face count is anomalous (`FACE_COUNT_ANOMALY`).

## What it does not do

- **No face recognition / identity.** The model (BlazeFace short-range) only locates faces; it
  never identifies who a person is and produces no embeddings.
- **No raw media leaves the browser.** Frames are decoded in-browser, analysed, and discarded.
  No images, video, screenshots, frames, tensors, or embeddings are submitted to the backend.
- **No cloud/third-party AI inference.** The model and the MediaPipe WASM runtime are served from
  this app's own `public/ai/` directory and run locally (WASM + WebGL).
- **No enforcement.** `FACE_COUNT_ANOMALY` is evidence-only. It is excluded from the deterministic
  warning, risk, termination, suspension, and retest logic by design (`ProctorSignalTypes`).
  Existing deterministic proctoring rules are unaffected and keep working when this detector is
  unavailable.

## Data flow

```
camera stream (browser, never transmitted)
   → MediaPipe BlazeFace (local WASM/WebGL, no network call)
   → faceCount + confidence (max per-frame detection score; null when 0 faces)
   → stabilization: FACE_ANOMALY_CONFIRMATIONS = 3 consecutive anomalous frames (faceCount ≠ 1; normal = exactly 1)
   → rate limit: cooldown 10 000 ms — one FACE_COUNT_ANOMALY signal per ~10 s while the anomaly persists
   → FACE_COUNT_ANOMALY { source: CLIENT_AI, confidence, durationMs, metadata: { faceCount: N } }
   → POST /api/proctor/attempts/{attemptId}/signals
   → proctor_events → Command Center (evidence event; never a warning trigger)
```

### Why these values (documentation required)
- `detectionIntervalMs = 250` (≈ 4 detections/sec) — inside the required 2–5/sec band; keeps CPU/GPU
  cost bounded.
- `anomalyConfirmations = 3` — at ≈ 4 Hz this requires ≈ 0.5–0.75 s of consecutive anomalous frames,
  so single-frame detector glitches and brief occlusions do not produce signals.
- `cooldownMs = 10 000` — while a face-count anomaly persists for 10 s, only one signal is sent
  (evidence sampling), not one per frame. If it persists past the cooldown, another signal is sent.

### Confidence semantics
- Confidence is the **maximum** per-face detection score in the analysed frame, normalised to 0–1.
  The max expresses the model's strongest belief that at least one of the counted boxes is a genuine
  face, avoiding penalising small/borderline faces when several are present.
- When no face is detected (`faceCount === 0`) there is no detection score, so confidence is omitted
  from the signal. Confidence is a descriptor of the observation, never an enforcement threshold.
- Normal (one-face) frames emit **no** signal; there is no `FACE_COUNT_NORMAL` event.

## Lifecycle & reliability

- Bound to the authenticated attempt via `attemptId` from session context (no client-supplied
  `studentId`). The detector starts only while an attempt is active and stops (camera released,
  model unloaded) when the attempt ends or when a retest changes the `attemptId`.
- The camera is requested exactly once per attempt. If the camera is denied or missing, or the
  model fails to load, the status is truthfully reported as **"Camera analysis unavailable"** and no
  observations are fabricated. No repeated permission requests, no retries.
- Detection pauses while the tab is hidden and while the component is unmounted; all timers,
  listeners, the held camera track, and the WASM model are disposed.
- A failed signal submission (network) never stops the loop and never changes enforcement state.

## Performance bookkeeping

The controller tracks init duration, per-detection duration, detection count, and signal count
internally (`FaceCountControllerStats`). This is developer diagnostics only — it is **not**
telemetry and is never sent to the backend.

## Research note (experimental, do not claim results)

Hypothesis: *Can local client-side signal extraction provide useful proctoring evidence while
reducing raw-media transmission and latency?* This detector is an **evidence generator**, not a
judgement. No precision/recall/F1/FPR/FNR or latency/bandwidth figures are claimed yet.

Future comparisons (when data exists):
- Variant A: human invigilator / screen-share WebRTC (existing).
- Variant B: server-side analysis of transmitted media.
- Variant C: this client-side detector (`CLIENT_AI`, `FACE_COUNT_ANOMALY`).

Future metrics to compare across variants: precision, recall, F1, false positive/negative rates,
detection latency, bandwidth, raw-media transmission volume, and the data-minimisation ratio.
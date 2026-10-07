'use client'
import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, CheckCircle2, FlaskConical, Play, RefreshCw, Trash2, Video, XCircle } from 'lucide-react'
import { Card, Header } from '@/components/shared'
import { useToast } from '@/components/feedback'
import { researchApi } from '@/services/researchApi'
import { proctorApi } from '@/services/proctorApi'
import { startFaceCountController, type FaceCountController, type FaceCountState } from '@/ai/faceCount'
import { useAuthStore } from '@/store/authStore'
import type { ProctorSignalInput } from '@/types/proctor'
import type { CommandCenterStudent } from '@/types/proctor'
import { RESEARCH_SCENARIOS, RESEARCH_SCENARIO_LABELS } from '@/types/research'
import type { ControlledConditions, ResearchRunSample, ResearchScenario } from '@/types/research'

const WINDOW_MS_DEFAULT = 3000
const WINDOW_MS_MIN = 1000
const WINDOW_MS_MAX = 30000

const SCENARIO_CONDITION_PRESETS: Record<ResearchScenario, ControlledConditions> = {
  NORMAL: { lighting: 'GOOD', cameraQuality: 'HD', network: 'STABLE', cameraAngle: 'FRONT' },
  WINDOW_BLUR: { lighting: 'GOOD', cameraQuality: 'HD', network: 'STABLE', cameraAngle: 'FRONT' },
  TAB_SWITCH: { lighting: 'GOOD', cameraQuality: 'HD', network: 'STABLE', cameraAngle: 'FRONT' },
  CAMERA_OFF: { lighting: 'LOW', cameraQuality: 'LOW', network: 'STABLE', cameraAngle: 'FRONT' },
  MULTIPLE_FACES: { lighting: 'GOOD', cameraQuality: 'HD', network: 'STABLE', cameraAngle: 'FRONT' },
  AUDIO_DETECTED: { lighting: 'GOOD', cameraQuality: 'HD', network: 'STABLE', cameraAngle: 'FRONT' },
  FULLSCREEN_EXIT: { lighting: 'GOOD', cameraQuality: 'HD', network: 'STABLE', cameraAngle: 'FRONT' },
  NETWORK_INTERRUPTION: { lighting: 'GOOD', cameraQuality: 'HD', network: 'INTERRUPTED', cameraAngle: 'FRONT' },
}

const REVIEW_LABEL_OPTIONS = [
  'NORMAL',
  'NO_ANOMALY',
  'ANOMALY',
  'TAB_SWITCH',
  'WINDOW_BLUR',
  'CAMERA_OFF',
  'AUDIO_DETECTED',
  'NETWORK_INTERRUPTION',
  'FULLSCREEN_EXIT',
  'MULTIPLE_PERSONS',
]

interface SignalRecorder {
  firstOccurredAtMs: number | null
  signalBytes: number
  record: (signal: ProctorSignalInput) => void
}

function createRecorder(): SignalRecorder {
  const recorder: SignalRecorder = {
    firstOccurredAtMs: null,
    signalBytes: 0,
    record(signal) {
      recorder.signalBytes += JSON.stringify(signal).length
      if (!signal.occurredAt) return
      const at = Date.parse(signal.occurredAt)
      if (Number.isFinite(at) && (recorder.firstOccurredAtMs === null || at < recorder.firstOccurredAtMs)) {
        recorder.firstOccurredAtMs = at
      }
    },
  }
  return recorder
}

function fmtBytes(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${value} B`
}

function ScenarioBadge({ scenario, expected }: { scenario: ResearchScenario; expected: boolean }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs ${expected ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'}`}>
      {RESEARCH_SCENARIO_LABELS[scenario]}
    </span>
  )
}

function SampleRow({
  sample,
  runId,
  currentUser,
  reviewsQueryKey,
}: {
  sample: ResearchRunSample
  runId: string
  currentUser: { id: string; name: string; email: string }
  reviewsQueryKey: (runSampleId: string) => void
}) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const [label, setLabel] = useState('')
  const [confidence, setConfidence] = useState('0.8')
  const [notes, setNotes] = useState('')

  const reviewsQuery = useQuery({
    queryKey: ['pilot-sample-reviews', sample.id],
    queryFn: () => researchApi.getRunSampleReviews(sample.id),
    select: data => data.data.data,
    enabled: sample.status === 'CAPTURED' && sample.researchSampleId !== null,
  })

  const reviewMutation = useMutation({
    mutationFn: () => researchApi.addReview(sample.researchSampleId as string, {
      label,
      confidence: Number(confidence),
      notes: notes.trim() || undefined,
    }),
    onSuccess: () => {
      toast?.success('Review saved')
      setLabel('')
      queryClient.invalidateQueries({ queryKey: ['pilot-sample-reviews', sample.id] })
      reviewsQueryKey(sample.id)
    },
    onError: () => toast?.error('Unable to save review'),
  })

  const reviews = reviewsQuery.data ?? null
  const reviewsByMe = reviews ? reviews.reviews.filter(r => r.reviewerId === currentUser.id) : []
  const reviewsByOthers = reviews ? reviews.reviews.filter(r => r.reviewerId !== currentUser.id) : []

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-48">
          <div className="flex flex-wrap items-center gap-2">
            <ScenarioBadge scenario={sample.scenario} expected={reviews != null && reviews.reviews.length === 0} />
          </div>
          <p className="mt-1 text-sm text-muted-foreground">
            {Object.entries(sample.condition)
              .map(([key, value]) => `${key}=${value}`)
              .join(' · ') || 'no conditions'}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <span className={`rounded-full bg-muted px-2 py-0.5`}>{sample.status}</span>
          {sample.measuredLatencyMs !== null && <span title="Measured latency">latency {sample.measuredLatencyMs} ms</span>}
          {sample.rawMediaBytes !== null && <span title="Raw media bytes">{fmtBytes(sample.rawMediaBytes)} raw</span>}
          {sample.signalBytes !== null && <span title="Signal bytes">{fmtBytes(sample.signalBytes)} sig</span>}
          {sample.signalCount !== null && <span title="Stored proctor signals in window">{sample.signalCount} signals</span>}
        </div>
      </div>

      {sample.status === 'CAPTURED' && sample.researchSampleId !== null && (
        <div className="mt-3 border-t pt-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs text-muted-foreground">Human review</span>
            {reviews && reviews.reviews.map(review => (
              <span key={review.reviewerId} className="rounded-full bg-muted px-2 py-0.5 text-xs">
                {review.label} · {review.reviewerName} ({review.reviewerEmail})
              </span>
            ))}
          </div>
          {reviews && reviews.reviews.length === 0 && (
            <p className="mt-2 text-xs text-muted-foreground">
              No reviews yet. Review as a second distinct administrator to complete the A/B pair.
            </p>
          )}
          {reviews && !reviews.currentUserReviewed && (
            <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={e => { e.preventDefault(); if (label) reviewMutation.mutate() }}>
              <label className="block text-xs">Label
                <select className="field mt-1" value={label} onChange={e => setLabel(e.target.value)}>
                  <option value="">Select…</option>
                  {REVIEW_LABEL_OPTIONS.map(option => <option key={option} value={option}>{option}</option>)}
                </select>
              </label>
              <label className="block text-xs">Confidence (0–1)
                <input type="number" min={0} max={1} step={0.1} className="field mt-1" value={confidence} onChange={e => setConfidence(e.target.value)} />
              </label>
              <label className="block flex-1 text-xs">Notes
                <input className="field mt-1" value={notes} onChange={e => setNotes(e.target.value)} placeholder="What the evidence shows" />
              </label>
              <button disabled={reviewMutation.isPending || !label} className="flex items-center gap-1 rounded-lg bg-primary px-3 py-2 text-xs text-primary-foreground disabled:opacity-50">
                <CheckCircle2 size={14} /> {reviewMutation.isPending ? 'Saving…' : 'Save review'}
              </button>
            </form>
          )}
          {reviews && reviews.currentUserReviewed && (
            <p className="mt-2 text-xs text-muted-foreground">
              Reviewed by you ({currentUser.email}). Sign out and review as a second distinct administrator to complete the A/B pair.
            </p>
          )}
          <div className="mt-2 flex flex-wrap gap-2 text-xs text-muted-foreground">
            {reviewsByMe.map(review => <span key="me" className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-emerald-700">You: {review.label}</span>)}
            {reviewsByOthers.map(review => <span key={review.reviewerId} className="rounded-full bg-muted px-2 py-0.5">Other: {review.label}</span>)}
          </div>
        </div>
      )}
    </Card>
  )
}

export function ResearchPilot() {
  const toast = useToast()
  const queryClient = useQueryClient()
  const currentUser = useAuthStore(state => state.user)

  const [experimentId, setExperimentId] = useState('')
  const [selectedAttemptId, setSelectedAttemptId] = useState('')
  const [manualAttemptId, setManualAttemptId] = useState('')
  const [windowMs, setWindowMs] = useState(WINDOW_MS_DEFAULT)
  const [measuringSampleId, setMeasuringSampleId] = useState<string | null>(null)
  const [measurementState, setMeasurementState] = useState<FaceCountState | null>(null)
  const measurementRef = useRef<{ timer: number; controller: FaceCountController | null; recorder: SignalRecorder; startMs: number; runId: string; sampleId: string } | null>(null)
  const observedAtMsRef = useRef<Record<string, number>>({})

  const experimentsQuery = useQuery({
    queryKey: ['research-experiments'],
    queryFn: researchApi.listExperiments,
    select: data => data.data.data,
  })

  const experiments = useMemo(() => experimentsQuery.data ?? [], [experimentsQuery.data])
  const selectedExperiment = experiments.find(e => e.id === experimentId) ?? null

  const runsQuery = useQuery({
    queryKey: ['pilot-runs', experimentId],
    queryFn: () => researchApi.listRuns(experimentId),
    select: data => data.data.data,
    enabled: experimentId.length > 0,
  })

  const activeRun = useMemo(() => {
    const runs = runsQuery.data ?? []
    return runs.find(r => r.status === 'RUNNING') ?? runs.find(r => r.status === 'PLANNED') ?? null
  }, [runsQuery.data])

  const runDetailQuery = useQuery({
    queryKey: ['pilot-run-detail', activeRun?.id],
    queryFn: () => researchApi.getRun(activeRun?.id as string),
    select: data => data.data.data,
    enabled: activeRun !== null,
  })

  const commandCenterQuery = useQuery({
    queryKey: ['pilot-command-center', selectedExperiment?.examId],
    queryFn: () => proctorApi.commandCenter(selectedExperiment?.examId as string),
    select: data => data.data.data,
    enabled: selectedExperiment?.examId != null && selectedExperiment.examId.length > 0,
  })

  const registeredAttempts = useMemo(() => {
    const cc = commandCenterQuery.data
    if (!cc) return []
    return cc.roster.filter((student): student is CommandCenterStudent & { attemptId: string } => student.attemptId != null && student.attemptId.length > 0)
  }, [commandCenterQuery.data])

  const activateMutation = useMutation({
    mutationFn: () => researchApi.updateExperimentStatus(experimentId, 'ACTIVE'),
    onSuccess: () => {
      toast?.success('Experiment activated')
      queryClient.invalidateQueries({ queryKey: ['research-experiments'] })
    },
    onError: () => toast?.error('Unable to activate experiment'),
  })

  const createRunMutation = useMutation({
    mutationFn: () => researchApi.createRun(experimentId, { runCode: `pilot-${Date.now().toString().slice(-8)}` }),
    onSuccess: () => {
      toast?.success('Run created')
      queryClient.invalidateQueries({ queryKey: ['pilot-runs', experimentId] })
    },
    onError: () => toast?.error('Unable to create run'),
  })

  const startRunMutation = useMutation({
    mutationFn: (runId: string) => researchApi.startRun(runId),
    onSuccess: run => {
      toast?.success('Run started')
      queryClient.invalidateQueries({ queryKey: ['pilot-runs', experimentId] })
      queryClient.invalidateQueries({ queryKey: ['pilot-run-detail', run.data.data.id] })
    },
    onError: () => toast?.error('Unable to start run'),
  })

  const completeRunMutation = useMutation({
    mutationFn: (runId: string) => researchApi.completeRun(runId),
    onSuccess: () => {
      toast?.success('Run completed')
      queryClient.invalidateQueries({ queryKey: ['pilot-runs', experimentId] })
      queryClient.invalidateQueries({ queryKey: ['pilot-run-detail', activeRun?.id] })
    },
    onError: () => toast?.error('Unable to complete run while a sample is still capturing.'),
  })

  const planAllMutation = useMutation({
    mutationFn: async ({ runId, attemptId }: { runId: string; attemptId: string }) => {
      for (const scenario of RESEARCH_SCENARIOS) {
        await researchApi.createRunSample(runId, {
          attemptId,
          scenario,
          conditions: SCENARIO_CONDITION_PRESETS[scenario],
        })
      }
    },
    onSuccess: () => {
      toast?.success('All scenarios planned')
      queryClient.invalidateQueries({ queryKey: ['pilot-run-detail', activeRun?.id] })
    },
    onError: () => toast?.error('Unable to plan all scenarios'),
  })

  const observeMutation = useMutation({
    mutationFn: ({ runId, sampleId }: { runId: string; sampleId: string }) => researchApi.observeRunSample(runId, sampleId),
    onSuccess: (response) => {
      observedAtMsRef.current[response.data.data.id] = Date.now()
      toast?.success('Observation started')
      queryClient.invalidateQueries({ queryKey: ['pilot-run-detail', activeRun?.id] })
    },
    onError: () => toast?.error('Unable to observe sample'),
  })

  const captureMutation = useMutation({
    mutationFn: ({ runId, sampleId, body }: { runId: string; sampleId: string; body: { endedAt: string; measuredLatencyMs?: number | null; rawMediaBytes?: number | null; signalBytes?: number | null } }) =>
      researchApi.captureRunSample(runId, sampleId, {
        endedAt: body.endedAt,
        measuredLatencyMs: body.measuredLatencyMs ?? undefined,
        rawMediaBytes: body.rawMediaBytes ?? undefined,
        signalBytes: body.signalBytes ?? undefined,
      }),
    onSuccess: () => {
      toast?.success('Sample captured')
      queryClient.invalidateQueries({ queryKey: ['pilot-run-detail', activeRun?.id] })
    },
    onError: () => toast?.error('Unable to capture sample'),
  })

  function measureAndCapture(sample: ResearchRunSample, runId: string) {
    if (measurementRef.current || !currentUser) return
    const startMs = Date.now()
    setMeasuringSampleId(sample.id)
    setMeasurementState({ status: 'initializing', faceCount: null, confidence: null, detail: 'Starting camera analysis' })

    const recorder = createRecorder()
    let controller: FaceCountController | null = null
    const timer = window.setTimeout(() => {
      const session = measurementRef.current
      measurementRef.current = null
      setMeasuringSampleId(null)
      setMeasurementState(null)
      try { controller?.dispose() } catch { /* ignore */ }

      const firstOccurredMs = recorder.firstOccurredAtMs
      const observedAtMs = observedAtMsRef.current[sample.id]
      const latency = firstOccurredMs != null && observedAtMs != null ? Math.max(0, firstOccurredMs - observedAtMs) : null
      const raw = controller ? controller.getRawMediaBytes() : 0
      const rawMediaBytes = raw > 0 ? raw : null
      const signalBytes = rawMediaBytes !== null ? recorder.signalBytes : null
      if (!session) return

      captureMutation.mutate({
        runId,
        sampleId: sample.id,
        body: {
          endedAt: new Date().toISOString(),
          measuredLatencyMs: latency,
          rawMediaBytes,
          signalBytes,
        },
      })
    }, windowMs)

    measurementRef.current = { timer, controller: null, recorder, startMs, runId, sampleId: sample.id }
    startFaceCountController({
      submit: signal => { recorder.record(signal); return Promise.resolve() },
      onStateChange: setMeasurementState,
    })
      .then((handle) => {
        if (measurementRef.current?.sampleId === sample.id) {
          measurementRef.current.controller = handle
          controller = handle
          setMeasurementState({ status: 'active', faceCount: null, confidence: null })
        } else {
          handle.dispose()
        }
      })
      .catch(() => {
        setMeasurementState({ status: 'unavailable', faceCount: null, confidence: null, detail: 'Camera unavailable — measurements will be honest missing values.' })
      })
  }

  function cancelMeasurement() {
    const session = measurementRef.current
    if (!session) return
    measurementRef.current = null
    setMeasuringSampleId(null)
    setMeasurementState(null)
    window.clearTimeout(session.timer)
    session.controller?.dispose()
  }

  const samples = runDetailQuery.data?.samples ?? []
  const dataQuality = runDetailQuery.data?.dataQuality ?? null
  const challengeAttemptId = selectedAttemptId || manualAttemptId.trim()

  return (
    <>
      <Header title="Research pilot" description="Operate a controlled proctoring capture run against a real live attempt. Observational only — it never alters enforcement, warnings, or access." />
      <Card className="p-4">
        <div className="flex flex-wrap items-end gap-3">
          <label className="block min-w-64 text-sm font-medium">
            Experiment
            <select className="field mt-1" value={experimentId} onChange={e => setExperimentId(e.target.value)}>
              <option value="">Select an experiment…</option>
              {experiments.map(e => <option key={e.id} value={e.id}>{e.name} · {e.status}</option>)}
            </select>
          </label>
          {selectedExperiment && selectedExperiment.status !== 'ACTIVE' && selectedExperiment.status !== 'COMPLETED' && (
            <button onClick={() => activateMutation.mutate()} disabled={activateMutation.isPending} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-60">
              <Play size={15} /> {activateMutation.isPending ? 'Activating…' : 'Activate experiment'}
            </button>
          )}
          {selectedExperiment?.examId && (
            <a href={`/teacher/exams/${selectedExperiment.examId}/room`} className="rounded-lg border border-border px-3 py-2 text-xs hover:bg-muted">Open exam room</a>
          )}
        </div>
      </Card>

      {selectedExperiment && (
        <Card className="mt-4 p-4">
          <div className="flex flex-wrap items-center gap-3">
            <div className="min-w-56">
              <p className="text-xs text-muted-foreground">Attempt under observation</p>
              <select className="field mt-1" value={selectedAttemptId} onChange={e => setSelectedAttemptId(e.target.value)} disabled={registeredAttempts.length === 0}>
                <option value="">{registeredAttempts.length ? 'Select an active attempt…' : 'No active attempts on this exam'}</option>
                {registeredAttempts.map(student => (
                  <option key={student.attemptId} value={student.attemptId}>{student.studentName} ({student.studentEmail})</option>
                ))}
              </select>
            </div>
            <label className="block min-w-56 text-sm font-medium">
              Or attempt ID
              <input className="field mt-1" value={manualAttemptId} onChange={e => setManualAttemptId(e.target.value)} placeholder="Paste an attempt id" />
            </label>
            <label className="block min-w-36 text-sm font-medium">
              Window (ms)
              <input type="number" min={WINDOW_MS_MIN} max={WINDOW_MS_MAX} value={windowMs} onChange={e => setWindowMs(Number(e.target.value))} className="field mt-1" />
            </label>
            <button disabled={!activeRun || activeRun.status !== 'PLANNED'} onClick={() => activeRun && startRunMutation.mutate(activeRun.id)} className="rounded-lg border border-border px-3 py-2 text-xs disabled:opacity-50">
              {activeRun?.status === 'RUNNING' ? 'Run is running' : 'Start run'}
            </button>
            {!activeRun && <button disabled={createRunMutation.isPending} onClick={() => createRunMutation.mutate()} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-60"><RefreshCw size={14} /> {createRunMutation.isPending ? 'Creating…' : 'Create run'}</button>}
          </div>
        </Card>
      )}

      {activeRun && (
        <Card className="mt-4 p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-sm font-semibold">Run {activeRun.runCode} · {activeRun.status}</p>
              {challengeAttemptId && <p className="mt-1 text-xs text-muted-foreground">Attempt {challengeAttemptId}</p>}
            </div>
            <div className="flex flex-wrap gap-2">
              {activeRun.status === 'PLANNED' && challengeAttemptId && (
                <button onClick={() => planAllMutation.mutate({ runId: activeRun.id, attemptId: challengeAttemptId })} disabled={planAllMutation.isPending || !challengeAttemptId} className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm text-primary-foreground disabled:opacity-60">
                  <FlaskConical size={15} /> {planAllMutation.isPending ? 'Planning…' : 'Plan all 8 scenarios'}
                </button>
              )}
              {(activeRun.status === 'RUNNING' || activeRun.status === 'PLANNED') && (
                <button onClick={() => completeRunMutation.mutate(activeRun.id)} disabled={completeRunMutation.isPending} className="rounded-lg border border-border px-3 py-2 text-xs disabled:opacity-50">
                  Complete run
                </button>
              )}
            </div>
          </div>
          {activeRun.status === 'RUNNING' && dataQuality && (
            <div className="mt-3 grid grid-cols-2 gap-2 border-t pt-3 sm:grid-cols-5">
              <div className="rounded-lg bg-muted p-2 text-center"><p className="text-xs text-muted-foreground">Captured</p><p className="text-sm font-semibold">{dataQuality.captured}</p></div>
              <div className="rounded-lg bg-muted p-2 text-center"><p className="text-xs text-muted-foreground">Reviewed</p><p className="text-sm font-semibold">{dataQuality.reviewed}</p></div>
              <div className="rounded-lg bg-muted p-2 text-center"><p className="text-xs text-muted-foreground">Tied</p><p className="text-sm font-semibold">{dataQuality.tied}</p></div>
              <div className="rounded-lg bg-muted p-2 text-center"><p className="text-xs text-muted-foreground">Missing latency</p><p className="text-sm font-semibold">{dataQuality.missingMeasuredLatency}</p></div>
              <div className="rounded-lg bg-muted p-2 text-center"><p className="text-xs text-muted-foreground">Missing bandwidth</p><p className="text-sm font-semibold">{dataQuality.missingBandwidth}</p></div>
            </div>
          )}
        </Card>
      )}

      {activeRun && samples.length > 0 && (
        <div className="mt-4 space-y-3">
          {samples.map(sample => (
            <div key={sample.id}>
              <SampleRow sample={sample} runId={activeRun.id} currentUser={{ id: currentUser?.id ?? '', name: currentUser?.name ?? '', email: currentUser?.email ?? '' }} reviewsQueryKey={() => queryClient.invalidateQueries({ queryKey: ['pilot-run-detail', activeRun.id] })} />
              {sample.status === 'CAPTURING' && (
                <div className="mt-2 flex flex-wrap items-center gap-2 rounded-xl border border-dashed p-2">
                  {measuringSampleId !== sample.id ? (
                    <button onClick={() => measureAndCapture(sample, activeRun.id)} disabled={!currentUser} className="flex items-center gap-2 rounded-lg bg-primary px-3 py-2 text-xs text-primary-foreground disabled:opacity-50">
                      <Video size={14} /> Measure & capture (measurement window {windowMs} ms)
                    </button>
                  ) : (
                    <>
                      <span className="flex items-center gap-2 text-xs text-muted-foreground"><Activity size={14} className="animate-pulse" /> Recording window…</span>
                      <span className="rounded-full bg-muted px-2 py-0.5 text-xs">{measurementState?.status}{measurementState?.detail ? ` — ${measurementState.detail}` : ''}</span>
                      <button onClick={cancelMeasurement} className="flex items-center gap-1 rounded-lg border border-border px-2 py-1.5 text-xs"><XCircle size={14} /> Cancel</button>
                    </>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {activeRun && samples.length === 0 && activeRun.status === 'PLANNED' && challengeAttemptId && (
        <Card className="mt-4 flex items-center gap-2 p-6 text-sm text-muted-foreground">
          <Trash2 size={16} /> No samples planned yet. Plan all 8 scenarios to build the pilot matrix.
        </Card>
      )}
    </>
  )
}
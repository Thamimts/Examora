import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, AlertTriangle, BadgeCheck, BarChart3, CheckCircle2, ChevronDown, Eye, FlaskConical, Gauge, ListChecks, Play, Plus, Save, Square, XCircle } from 'lucide-react'
import { Card } from '@/components/shared'
import { useToast } from '@/components/feedback'
import { researchApi } from '@/services/researchApi'
import { RESEARCH_SCENARIOS, RESEARCH_SCENARIO_LABELS, type ResearchEvaluatorResult, type ResearchRun, type ResearchRunEvaluation, type ResearchRunSample, type ResearchRunSampleDetail, type ResearchScenario } from '@/types/research'

const RUN_STATUS_TONE: Record<string, string> = {
  PLANNED: 'bg-muted text-muted-foreground',
  RUNNING: 'bg-emerald-500/15 text-emerald-600',
  COMPLETED: 'bg-primary/15 text-primary',
  CANCELLED: 'bg-rose-500/15 text-rose-600',
}

const SAMPLE_STATUS_TONE: Record<string, string> = {
  PLANNED: 'bg-muted text-muted-foreground',
  CAPTURING: 'bg-amber-500/15 text-amber-600',
  CAPTURED: 'bg-emerald-500/15 text-emerald-600',
}

function fmtMs(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return `${value.toFixed(0)} ms`
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border bg-card p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-sm font-semibold text-foreground">{value}</p>
    </div>
  )
}

export function ResearchRunsPanel({ experimentId }: { experimentId: string }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [runCode, setRunCode] = useState('')
  const [selectedRunId, setSelectedRunId] = useState<string>('')
  const [attemptId, setAttemptId] = useState('')
  const [scenario, setScenario] = useState<ResearchScenario>('NORMAL')
  const [conditions, setConditions] = useState<Record<string, string>>(
    { lighting: 'GOOD', cameraQuality: 'HD', network: 'STABLE', cameraAngle: 'FRONT' }
  )
  const [evaluationOpen, setEvaluationOpen] = useState(false)
  const observedAt = useRef<Record<string, number>>({})

  const runsQuery = useQuery({
    queryKey: ['research-runs', experimentId],
    queryFn: () => researchApi.listRuns(experimentId),
    select: data => data.data.data,
    enabled: experimentId.length > 0,
  })

  const runDetailQuery = useQuery({
    queryKey: ['research-run', selectedRunId],
    queryFn: () => researchApi.getRun(selectedRunId),
    select: data => data.data.data,
    enabled: selectedRunId.length > 0,
  })

  const runEvaluationQuery = useQuery({
    queryKey: ['research-run-evaluation', selectedRunId],
    queryFn: () => researchApi.evaluateRun(selectedRunId),
    select: data => data.data.data,
    enabled: selectedRunId.length > 0 && evaluationOpen,
    staleTime: 0,
  })

  const scenariosQuery = useQuery({
    queryKey: ['research-scenario-instructions'],
    queryFn: researchApi.scenarioInstructions,
    select: data => data.data.data,
  })

  const conditionsQuery = useQuery({
    queryKey: ['research-condition-catalog'],
    queryFn: researchApi.conditionCatalog,
    select: data => data.data.data,
  })

  const invalidateRuns = () => {
    if (selectedRunId) queryClient.invalidateQueries({ queryKey: ['research-run', selectedRunId] })
    queryClient.invalidateQueries({ queryKey: ['research-run-evaluation', selectedRunId] })
    queryClient.invalidateQueries({ queryKey: ['research-runs', experimentId] })
    queryClient.invalidateQueries({ queryKey: ['research-evaluation', experimentId] })
  }

  const createRunMutation = useMutation({
    mutationFn: (body: { runCode: string }) => researchApi.createRun(experimentId, body),
    onSuccess: data => {
      toast?.success('Run created')
      queryClient.invalidateQueries({ queryKey: ['research-runs', experimentId] })
      setSelectedRunId(data.data.data.id)
      setRunCode('')
    },
    onError: () => toast?.error('Unable to create run'),
  })

  const transitionMutation = useMutation({
    mutationFn: ({ runId, action }: { runId: string; action: 'start' | 'complete' | 'cancel' }) =>
      action === 'start' ? researchApi.startRun(runId)
        : action === 'complete' ? researchApi.completeRun(runId)
        : researchApi.cancelRun(runId),
    onSuccess: () => {
      toast?.success('Run updated')
      invalidateRuns()
    },
    onError: () => toast?.error('Run transition was rejected'),
  })

  const createSampleMutation = useMutation({
    mutationFn: (body: { attemptId: string; scenario: ResearchScenario; conditions: Record<string, string> }) =>
      researchApi.createRunSample(selectedRunId, body),
    onSuccess: () => {
      toast?.success('Planned sample created')
      invalidateRuns()
    },
    onError: () => toast?.error('Unable to plan sample'),
  })

  const observeMutation = useMutation({
    mutationFn: (sample: ResearchRunSample) => researchApi.observeRunSample(selectedRunId, sample.id),
    onSuccess: (_data, sample) => {
      observedAt.current[sample.id] = Date.now()
      toast?.success('Observation started')
      invalidateRuns()
    },
    onError: () => toast?.error('Observation rejected'),
  })

  const captureMutation = useMutation({
    mutationFn: (sample: ResearchRunSample) => {
      const started = observedAt.current[sample.id] ?? Date.now()
      const ended = new Date(started + 1500)
      return researchApi.captureRunSample(selectedRunId, sample.id, { endedAt: ended.toISOString() })
    },
    onSuccess: (_data, sample) => {
      delete observedAt.current[sample.id]
      toast?.success('Sample captured')
      invalidateRuns()
    },
    onError: () => toast?.error('Capture rejected — window or state invalid'),
  })

  const reviewMutation = useMutation({
    mutationFn: ({ sample, label, notes }: { sample: ResearchRunSample; label: string; notes?: string }) =>
      researchApi.addReview(sample.researchSampleId!, { label, notes: notes?.trim() || undefined }),
    onSuccess: () => {
      toast?.success('Sample reviewed')
      invalidateRuns()
    },
    onError: () => toast?.error('Unable to review sample'),
  })

  const runs = useMemo(() => runsQuery.data ?? [], [runsQuery.data])
  const detail = runDetailQuery.data
  const instructions = useMemo(() => scenariosQuery.data ?? [], [scenariosQuery.data])
  const catalog = useMemo(() => conditionsQuery.data ?? [], [conditionsQuery.data])
  const scenarioInstruction = instructions.find(i => i.scenario === scenario)
  const canCaptureNow = (sample: ResearchRunSample) =>
    sample.status === 'CAPTURING' && (observedAt.current[sample.id] ?? 0) + 1100 <= Date.now()

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <div className="flex items-center gap-2">
          <FlaskConical size={16} className="text-muted-foreground" />
          <h3 className="text-sm font-semibold">Controlled runs</h3>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          Research runs collect planned (scenario × controlled condition) samples. Observation start is
          server-stamped; capture is allowed only after observing. Scenario intent is researcher metadata, never
          automatic ground truth. No raw media or biometric data is persisted.
        </p>
        <form
          className="mt-3 flex flex-wrap items-end gap-3"
          onSubmit={e => {
            e.preventDefault()
            if (runCode.trim()) createRunMutation.mutate({ runCode: runCode.trim() })
          }}
        >
          <label className="block min-w-52 text-sm font-medium">
            Run code
            <input className="field mt-1" value={runCode} onChange={e => setRunCode(e.target.value)}
              placeholder="e.g. run-2026-01" maxLength={40} />
          </label>
          <button
            disabled={createRunMutation.isPending || runCode.trim().length === 0}
            className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60"
          >
            <Plus size={15} /> {createRunMutation.isPending ? 'Creating…' : 'Create run'}
          </button>
        </form>
        {runs.length > 0 && (
          <div className="mt-3 space-y-2">
            {runs.map(run => (
              <div key={run.id}
                className={`flex flex-wrap items-center gap-3 rounded-xl border p-3 ${selectedRunId === run.id ? 'border-primary/40 bg-muted/40' : 'bg-card'}`}>
                <button type="button" onClick={() => setSelectedRunId(run.id)}
                  className="text-left">
                  <p className="text-sm font-semibold">{run.runCode} {run.notes ? `· ${run.notes}` : ''}</p>
                  <p className="text-xs text-muted-foreground">
                    {run.datasetVersion} · created {run.createdAt ? new Date(run.createdAt).toLocaleString() : '—'}
                  </p>
                </button>
                <span className={`rounded-full px-2 py-0.5 text-xs ${RUN_STATUS_TONE[run.status]}`}>{run.status}</span>
                <div className="ml-auto flex items-center gap-2">
                  {run.status === 'PLANNED' && (
                    <button onClick={() => transitionMutation.mutate({ runId: run.id, action: 'start' })}
                      className="flex items-center gap-1 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground">
                      <Play size={13} /> Start
                    </button>
                  )}
                  {run.status === 'RUNNING' && (
                    <>
                      <button onClick={() => transitionMutation.mutate({ runId: run.id, action: 'complete' })}
                        className="flex items-center gap-1 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground">
                        <CheckCircle2 size={13} /> Complete
                      </button>
                      <button onClick={() => transitionMutation.mutate({ runId: run.id, action: 'cancel' })}
                        className="flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs font-medium text-muted-foreground">
                        <XCircle size={13} /> Cancel
                      </button>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {selectedRunId && runsQuery.data && !detail && (
        <Card className="p-4 text-sm text-muted-foreground">Select a run to open it.</Card>
      )}

      {detail && (
        <>
          <Card className="p-4">
            <div className="flex flex-wrap items-center gap-3">
              <div className="min-w-48">
                <p className="text-sm font-semibold">{detail.run.runCode}</p>
                <p className="text-xs text-muted-foreground">
                  {detail.run.status} · {detail.run.datasetVersion}
                  {detail.run.startedAt ? ` · started ${new Date(detail.run.startedAt).toLocaleString()}` : ''}
                  {detail.run.endedAt ? ` · ended ${new Date(detail.run.endedAt).toLocaleString()}` : ''}
                </p>
              </div>
              <div className="grid flex-1 grid-cols-2 gap-2 sm:grid-cols-4">
                <Metric label="Planned" value={String(detail.dataQuality.planned)} />
                <Metric label="Capturing" value={String(detail.dataQuality.capturing)} />
                <Metric label="Captured" value={String(detail.dataQuality.captured)} />
                <Metric label="Evaluable" value={String(detail.dataQuality.evaluable)} />
              </div>
              <button
                type="button"
                onClick={() => setEvaluationOpen(open => !open)}
                className={`flex items-center gap-2 rounded-xl border px-3 py-2 text-sm font-medium ${evaluationOpen ? 'border-primary/40 bg-muted/40 text-primary' : 'text-muted-foreground'}`}
              >
                <Activity size={15} /> {evaluationOpen ? 'Hide evaluation' : 'Run evaluation'}
              </button>
            </div>
            <div className="mt-3 grid grid-cols-2 gap-2 border-t pt-3 sm:grid-cols-5">
              <Metric label="Reviewed" value={String(detail.dataQuality.reviewed)} />
              <Metric label="Unreviewed" value={String(detail.dataQuality.unreviewed)} />
              <Metric label="Tied" value={String(detail.dataQuality.tied)} />
              <Metric label="Invalid" value={String(detail.dataQuality.invalid)} />
              <Metric label="Unexpected signals" value={String(detail.dataQuality.unexpectedSignals)} />
              <Metric label="Missing signals" value={String(detail.dataQuality.missingSignals)} />
              <Metric label="Scenario agreement" value={String(detail.dataQuality.scenarioGroundTruthAgreement)} />
              <Metric label="Scenario disagreement" value={String(detail.dataQuality.scenarioGroundTruthDisagreement)} />
              <Metric label="Missing latency" value={String(detail.dataQuality.missingMeasuredLatency)} />
              <Metric label="Missing bandwidth" value={String(detail.dataQuality.missingBandwidth)} />
            </div>
          </Card>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <div className="flex items-center gap-2">
                <GridIcon />
                <h3 className="text-sm font-semibold">Scenario × condition matrix</h3>
              </div>
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">Scenario</th>
                      <th className="py-2 pr-3">Planned</th>
                      <th className="py-2 pr-3">Captured</th>
                      <th className="py-2 pr-3">Reviewed</th>
                      <th className="py-2 pr-3">Evaluable</th>
                      <th className="py-2 pr-3">Condition cells</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.matrix.map(row => (
                      <tr key={row.scenario} className="border-b last:border-0">
                        <td className="py-2 pr-3 font-medium">{row.scenario}</td>
                        <td className="py-2 pr-3">{row.planned}</td>
                        <td className="py-2 pr-3">{row.captured}</td>
                        <td className="py-2 pr-3">{row.reviewed}</td>
                        <td className="py-2 pr-3">{row.evaluable}</td>
                        <td className="py-2 pr-3 text-xs text-muted-foreground">
                          {(row.conditionsKey ?? []).join(' · ') || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card className="p-4">
              <div className="flex items-center gap-2">
                <ListChecks size={16} className="text-muted-foreground" />
                <h3 className="text-sm font-semibold">Plan a controlled sample</h3>
              </div>
              <form
                className="mt-3 space-y-3"
                onSubmit={e => {
                  e.preventDefault()
                  if (attemptId.trim()) {
                    createSampleMutation.mutate({ attemptId: attemptId.trim(), scenario, conditions })
                  }
                }}
              >
                <label className="block text-sm font-medium">
                  Attempt ID (existing exam attempt in the experiment's exam)
                  <input className="field mt-1" value={attemptId} onChange={e => setAttemptId(e.target.value)}
                    placeholder="attempt UUID" />
                </label>
                <label className="block text-sm font-medium">
                  Scenario
                  <select className="field mt-1" value={scenario} onChange={e => setScenario(e.target.value as ResearchScenario)}>
                    {RESEARCH_SCENARIOS.map(s => (
                      <option key={s} value={s}>{s} — {RESEARCH_SCENARIO_LABELS[s]}</option>
                    ))}
                  </select>
                </label>
                {scenarioInstruction && (
                  <p className="rounded-lg bg-muted p-2 text-xs text-muted-foreground">
                    {scenarioInstruction.expectedAction} · expected signals: {scenarioInstruction.expectedSignals.join(', ') || 'none'}
                  </p>
                )}
                <div className="grid grid-cols-2 gap-3">
                  {catalog.map(entry => (
                    <label key={entry.key} className="block text-sm font-medium" title={entry.label}>
                      {entry.label}
                      <select
                        className="field mt-1"
                        value={conditions[entry.key]}
                        onChange={e => setConditions(prev => ({ ...prev, [entry.key]: e.target.value }))}
                      >
                        {entry.options.map(option => (
                          <option key={option.value} value={option.value}>{option.value} — {option.description}</option>
                        ))}
                      </select>
                    </label>
                  ))}
                </div>
                <button
                  disabled={createSampleMutation.isPending || attemptId.trim().length === 0}
                  className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60"
                >
                  <Save size={15} /> Plan sample
                </button>
              </form>
            </Card>
          </div>

          {detail.samples.length > 0 && (
            <Card className="p-4">
              <div className="flex items-center gap-2">
                <Eye size={16} className="text-muted-foreground" />
                <h3 className="text-sm font-semibold">Run samples</h3>
              </div>
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">Attempt</th>
                      <th className="py-2 pr-3">Scenario</th>
                      <th className="py-2 pr-3">Conditions</th>
                      <th className="py-2 pr-3">Signals</th>
                      <th className="py-2 pr-3">Status</th>
                      <th className="py-2 pr-3">Captured window</th>
                      <th className="py-2 pr-3">Review</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.samples.map(sample => (
                      <RunSampleReviewRow key={sample.id} sample={sample} runStatus={detail.run.status}
                        observe={{ observing: observeMutation.isPending, onObserve: () => observeMutation.mutate(sample) }}
                        capture={{ capturing: captureMutation.isPending, canCapture: canCaptureNow, onCapture: () => captureMutation.mutate(sample) }}
                        review={{ reviewing: reviewMutation.isPending, onReview: (label, notes) => reviewMutation.mutate({ sample, label, notes }) }}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}

          {evaluationOpen && (
            <Card className="p-4">
              <div className="flex items-center gap-2">
                <Activity size={16} className="text-muted-foreground" />
                <h3 className="text-sm font-semibold">Run evaluation</h3>
                {runEvaluationQuery.data?.snapshot?.evaluatedAt && (
                  <span className="ml-auto text-xs text-muted-foreground">
                    evaluated {new Date(runEvaluationQuery.data.snapshot.evaluatedAt).toLocaleString()}
                  </span>
                )}
              </div>
              {runEvaluationQuery.isPending ? (
                <p className="mt-3 text-sm text-muted-foreground">Evaluating run samples…</p>
              ) : runEvaluationQuery.isError ? (
                <p className="mt-3 text-sm text-rose-600">Evaluation could not be loaded.</p>
              ) : runEvaluationQuery.data ? (
                <RunEvaluationView eval={runEvaluationQuery.data} />
              ) : null}
            </Card>
          )}
        </>
      )}

      {!selectedRunId && (
        <Card className="flex items-center gap-2 p-6 text-sm text-muted-foreground">
          <Square size={14} /> Create or select a run to plan, observe, capture and review controlled samples.
        </Card>
      )}
    </div>
  )
}

function GridIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-muted-foreground">
      <rect x="3" y="3" width="7" height="7" rx="1" />
      <rect x="14" y="3" width="7" height="7" rx="1" />
      <rect x="3" y="14" width="7" height="7" rx="1" />
      <rect x="14" y="14" width="7" height="7" rx="1" />
    </svg>
  )
}

function RunSampleReviewRow({
  sample, runStatus, observe, capture, review,
}: {
  sample: ResearchRunSample
  runStatus: string
  observe: { observing: boolean; onObserve: () => void }
  capture: { capturing: boolean; canCapture: (s: ResearchRunSample) => boolean; onCapture: () => void }
  review: { reviewing: boolean; onReview: (label: string, notes?: string) => void }
}) {
  const [showDetail, setShowDetail] = useState(false)
  const [notes, setNotes] = useState('')

  const detailQuery = useQuery({
    queryKey: ['research-run-sample-detail', sample.id],
    queryFn: () => researchApi.getRunSampleDetail(sample.researchSampleId!),
    select: data => data.data.data,
    enabled: showDetail && !!sample.researchSampleId,
  })

  return (
    <>
      <tr className="border-b last:border-0">
        <td className="py-2 pr-3 font-mono text-xs">{sample.attemptId.slice(0, 13)}…</td>
        <td className="py-2 pr-3">
          <button type="button" onClick={() => setShowDetail(v => !v)}
            className="flex items-center gap-1 text-left font-medium">
            {sample.scenario}
            {sample.researchSampleId && (
              <ChevronDown size={12} className={`text-muted-foreground transition-transform ${showDetail ? 'rotate-180' : ''}`} />
            )}
          </button>
        </td>
        <td className="py-2 pr-3 text-xs text-muted-foreground">
          {Object.entries(sample.condition ?? {}).map(([k, v]) => `${k}=${v}`).join(' · ') || '—'}
        </td>
        <td className="py-2 pr-3">{sample.signalCount}</td>
        <td className="py-2 pr-3">
          <span className={`rounded-full px-2 py-0.5 text-xs ${SAMPLE_STATUS_TONE[sample.status]}`}>
            {sample.status}
          </span>
        </td>
        <td className="py-2 pr-3 text-xs">
          {sample.startedAt ? `${new Date(sample.startedAt).toLocaleTimeString()} → ${sample.endedAt ? new Date(sample.endedAt).toLocaleTimeString() : '…'}` : '—'}
        </td>
        <td className="py-2 pr-3">
          <div className="flex flex-wrap items-center gap-2">
            {sample.status === 'PLANNED' && runStatus === 'RUNNING' && (
              <button onClick={observe.onObserve}
                disabled={observe.observing}
                className="flex items-center gap-1 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground disabled:opacity-60">
                <Play size={12} /> Observe
              </button>
            )}
            {sample.status === 'CAPTURING' && runStatus === 'RUNNING' && (
              <button onClick={capture.onCapture}
                disabled={!capture.canCapture(sample)}
                className="flex items-center gap-1 rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50">
                <BadgeCheck size={12} /> Capture
              </button>
            )}
            {sample.status === 'CAPTURED' && sample.researchSampleId && !sample.reviewed && (
              <div className="flex flex-wrap items-center gap-1">
                <input
                  className="field w-36 py-1 text-xs"
                  value={notes}
                  maxLength={500}
                  placeholder="optional note (≤500)"
                  onChange={e => setNotes(e.target.value)}
                />
                <button onClick={() => review.onReview('ANOMALY', notes)}
                  disabled={review.reviewing}
                  className="rounded-lg border px-2 py-1 text-xs text-rose-600 disabled:opacity-60">
                  Anomaly
                </button>
                <button onClick={() => review.onReview('NORMAL', notes)}
                  disabled={review.reviewing}
                  className="rounded-lg border px-2 py-1 text-xs text-emerald-600 disabled:opacity-60">
                  Normal
                </button>
              </div>
            )}
            {sample.status === 'CAPTURED' && sample.reviewed && (
              <span className="text-xs text-muted-foreground">
                {sample.evaluated ? 'evaluable' : sample.tied ? 'tied' : 'reviewed'}
              </span>
            )}
            {sample.status === 'CAPTURED' && !sample.researchSampleId && (
              <span className="text-xs text-muted-foreground">no linked sample</span>
            )}
          </div>
        </td>
      </tr>
      {showDetail && sample.researchSampleId && (
        <tr className="border-b last:border-0 bg-muted/30">
          <td colSpan={7} className="px-3 py-2">
            {detailQuery.isPending ? (
              <p className="text-xs text-muted-foreground">Loading sample detail…</p>
            ) : detailQuery.data ? (
              <SampleDetailGrid detail={detailQuery.data} />
            ) : (
              <p className="text-xs text-rose-600">Sample detail could not be loaded.</p>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

function SampleDetailGrid({ detail }: { detail: ResearchRunSampleDetail }) {
  const predictions = (
    <div className="flex flex-wrap gap-x-3 gap-y-1">
      <span>baseline {detail.baselinePositive === true ? 'positive' : detail.baselinePositive === false ? 'negative' : 'not evaluated'}</span>
      <span>fusion {detail.fusionPositive === true ? 'positive' : detail.fusionPositive === false ? 'negative' : 'not evaluated'}</span>
      <span>ground truth {detail.groundTruthLabel ?? '—'}</span>
    </div>
  )
  return (
    <div className="grid gap-3 text-xs sm:grid-cols-2 lg:grid-cols-3">
      <div>
        <p className="font-medium text-muted-foreground">Signals</p>
        <p>{detail.signalCount} total</p>
        <p>types: {detail.signalTypes.map(t => `${t.type}×${t.count}`).join(', ') || 'none'}</p>
        <p>sources: {detail.signalSources.map(s => `${s.source}×${s.count}`).join(', ') || 'none'}</p>
      </div>
      <div>
        <p className="font-medium text-muted-foreground">Signals detail</p>
        <p>confidence {detail.minConfidence !== null ? `${detail.minConfidence?.toFixed(2)}–${detail.maxConfidence?.toFixed(2)}` : '—'} (mean {detail.meanConfidence !== null ? detail.meanConfidence?.toFixed(2) : '—'})</p>
        <p>max duration {detail.maxDurationMs !== null && detail.maxDurationMs !== undefined ? `${detail.maxDurationMs} ms` : '—'}</p>
        <p>latency {detail.measuredLatencyMs !== null && detail.measuredLatencyMs !== undefined ? `${detail.measuredLatencyMs} ms` : '—'}</p>
      </div>
      <div>
        <p className="font-medium text-muted-foreground">Window & predictions</p>
        <p>{detail.startedAt ? `${new Date(detail.startedAt).toLocaleTimeString()} → ${detail.endedAt ? new Date(detail.endedAt).toLocaleTimeString() : '…'}` : '—'}</p>
        {predictions}
        <p>scenario agreement {detail.scenarioAgreement ? 'yes' : 'no'} (intent is metadata, not ground truth)</p>
      </div>
    </div>
  )
}

function RunEvaluationView({ eval: ev }: { eval: ResearchRunEvaluation }) {
  const p = ev.progress
  const completion = ev.completion
  const sig = ev.snapshot
  const capturedPct = p.target > 0 ? Math.min(100, Math.round((p.captured / p.target) * 100)) : 0

  const checklist = [
    { label: `Capture target samples (${p.captured}/${p.target})`, done: p.captured >= p.target },
    { label: 'Review every captured sample', done: ev.dataQuality.unreviewed === 0 && ev.dataQuality.captured > 0 },
    { label: 'Resolve tied reviews via majority', done: ev.dataQuality.tied === 0 },
    { label: 'Run evaluation on evaluable samples', done: completion.readyForEvaluation },
  ]

  return (
    <div className="mt-3 space-y-4">
      <div className="rounded-xl border bg-muted/30 p-3">
        <div className="flex items-center gap-2">
          <ListChecks size={15} className="text-muted-foreground" />
          <h4 className="text-sm font-semibold">Collection checklist</h4>
        </div>
        <ul className="mt-2 grid gap-1 text-xs sm:grid-cols-2">
          {checklist.map(item => (
            <li key={item.label} className="flex items-center gap-2">
              {item.done
                ? <CheckCircle2 size={13} className="text-emerald-600" />
                : <Square size={13} className="text-muted-foreground" />}
              <span className={item.done ? '' : 'text-muted-foreground'}>{item.label}</span>
            </li>
          ))}
        </ul>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        <Metric label="Target samples" value={String(p.target)} />
        <Metric label="Planned" value={String(p.planned)} />
        <Metric label="Captured" value={String(p.captured)} />
        <Metric label="Evaluable" value={String(p.evaluable)} />
        <Metric label="Ready" value={completion.readyForEvaluation ? 'Yes' : 'No'} />
      </div>
      <div>
        <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
          <div className="h-full rounded-full bg-primary" style={{ width: `${capturedPct}%` }} />
        </div>
        <p className="mt-1 text-xs text-muted-foreground">{capturedPct}% of target captured</p>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="p-4">
          <div className="flex items-center gap-2">
            <BarChart3 size={16} className="text-muted-foreground" />
            <h4 className="text-sm font-semibold">Completion summary</h4>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
            <Metric label="Target" value={String(completion.targetObservations)} />
            <Metric label="Captured" value={String(completion.actualCaptured)} />
            <Metric label="Reviewed" value={String(completion.reviewed)} />
            <Metric label="Evaluable" value={String(completion.evaluable)} />
            <Metric label="Scenario cells" value={`${completion.scenarioCoverage}/${completion.scenarioCells}`} />
            <Metric label="Condition cells" value={`${completion.conditionCoverage}/${completion.conditionCells}`} />
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center gap-2">
            <Gauge size={16} className="text-muted-foreground" />
            <h4 className="text-sm font-semibold">Snapshot</h4>
          </div>
          <dl className="mt-3 space-y-1 text-xs">
            <p className="flex justify-between gap-2"><span className="text-muted-foreground">Dataset</span><span className="tabular-nums">{sig.datasetVersion}</span></p>
            <p className="flex justify-between gap-2"><span className="text-muted-foreground">Baseline version</span><span className="tabular-nums">{sig.baselineVersion}</span></p>
            <p className="flex justify-between gap-2"><span className="text-muted-foreground">Fusion version</span><span className="tabular-nums">{sig.fusionVersion}</span></p>
            <p className="flex justify-between gap-2"><span className="text-muted-foreground">Evaluable samples</span><span className="tabular-nums">{sig.evaluableSampleCount}</span></p>
            <p className="flex justify-between gap-2"><span className="text-muted-foreground">Evaluated at</span><span>{sig.evaluatedAt ? new Date(sig.evaluatedAt).toLocaleString() : '—'}</span></p>
          </dl>
        </Card>
      </div>

      <Card className="p-4">
        <div className="flex items-center gap-2">
          <BarChart3 size={16} className="text-muted-foreground" />
          <h4 className="text-sm font-semibold">Scenario progress</h4>
        </div>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-xs text-muted-foreground">
                <th className="py-2 pr-3">Scenario</th>
                <th className="py-2 pr-3">Target</th>
                <th className="py-2 pr-3">Captured</th>
                <th className="py-2 pr-3">Reviewed</th>
                <th className="py-2 pr-3">Evaluable</th>
                <th className="py-2 pr-3">Coverage</th>
              </tr>
            </thead>
            <tbody>
              {ev.scenarioProgress.map(row => (
                <tr key={row.scenario} className="border-b last:border-0">
                  <td className="py-2 pr-3 font-medium">{row.scenario}</td>
                  <td className="py-2 pr-3">{row.target}</td>
                  <td className="py-2 pr-3">{row.captured}</td>
                  <td className="py-2 pr-3">{row.reviewed}</td>
                  <td className="py-2 pr-3">{row.evaluable}</td>
                  <td className="py-2 pr-3">
                    <div className="h-1.5 w-24 overflow-hidden rounded-full bg-muted">
                      <div className="h-full rounded-full bg-primary"
                        style={{ width: `${row.target > 0 ? Math.min(100, Math.round((row.captured / row.target) * 100)) : 0}%` }} />
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <ConfusionCard title="Baseline" result={ev.baseline} />
        <ConfusionCard title="Fusion" result={ev.fusion} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card className="p-4">
          <div className="flex items-center gap-2">
            <ListChecks size={16} className="text-muted-foreground" />
            <h4 className="text-sm font-semibold">Condition distribution</h4>
          </div>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            {ev.conditionDistribution.map(entry => (
              <div key={entry.key} className="rounded-xl border p-3">
                <p className="text-xs font-medium text-muted-foreground">{entry.label}</p>
                <div className="mt-1.5 space-y-1">
                  {entry.values.length === 0 && <p className="text-xs text-muted-foreground">no captured values</p>}
                  {entry.values.map(v => (
                    <div key={v.value} className="flex items-center justify-between text-xs">
                      <span>{v.value}</span>
                      <span className="tabular-nums">{v.count}</span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center gap-2">
            <Gauge size={16} className="text-muted-foreground" />
            <h4 className="text-sm font-semibold">Latency & bandwidth</h4>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <Metric label="Latency measured" value={String(ev.latency.measuredCount)} />
            <Metric label="Latency mean" value={fmtMs(ev.latency.meanMs)} />
            <Metric label="Latency median" value={fmtMs(ev.latency.medianMs)} />
            <Metric label="Latency min / max" value={`${fmtMs(ev.latency.minMs)} / ${fmtMs(ev.latency.maxMs)}`} />
            <Metric label="Bandwidth measured" value={String(ev.bandwidth.measuredCount)} />
            <Metric label="Mean signal bytes" value={fmtBytes(ev.bandwidth.meanSignalBytes)} />
            <Metric label="Mean raw media bytes" value={fmtBytes(ev.bandwidth.meanRawMediaBytes)} />
            <Metric label="Reduction ratio" value={fmtPct(ev.bandwidth.meanDataMinimizationRatio)} />
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            {ev.bandwidth.measuredCount === 0 && 'No bandwidth measurements captured.'}
          </p>
        </Card>
      </div>

      {ev.disagreements.length > 0 && (
        <Card className="p-4">
          <div className="flex items-center gap-2">
            <AlertTriangle size={16} className="text-amber-500" />
            <h4 className="text-sm font-semibold">Baseline / fusion disagreements</h4>
          </div>
          <div className="mt-3 space-y-2">
            {ev.disagreements.map(d => (
              <div key={d.sampleId} className="rounded-xl border p-3 text-xs">
                <p className="font-medium">{d.scenario} · {d.conditionsKey || 'default conditions'}</p>
                <p className="mt-1 text-muted-foreground">
                  baseline {d.baselinePositive ? 'positive' : 'negative'} vs fusion {d.fusionPositive ? 'positive' : 'negative'}
                  · ground truth {d.groundTruthLabel}
                </p>
                <p className="mt-1 text-muted-foreground">
                  signals {d.signalTypes.map(t => `${t.type}×${t.count}`).join(', ') || 'none'}
                  {d.meanConfidence !== null && d.meanConfidence !== undefined ? ` · confidence ${d.meanConfidence.toFixed(2)}` : ''}
                  {d.maxDurationMs !== null && d.maxDurationMs !== undefined ? ` · max duration ${d.maxDurationMs} ms` : ''}
                </p>
              </div>
            ))}
          </div>
        </Card>
      )}

      {ev.conditions.length > 0 && (
        <Card className="p-4">
          <div className="flex items-center gap-2">
            <BarChart3 size={16} className="text-muted-foreground" />
            <h4 className="text-sm font-semibold">Condition breakdown</h4>
          </div>
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">Condition</th>
                  <th className="py-2 pr-3">Value</th>
                  <th className="py-2 pr-3">Samples</th>
                  <th className="py-2 pr-3">Baseline TP/FP</th>
                  <th className="py-2 pr-3">Baseline FDR</th>
                  <th className="py-2 pr-3">Fusion TP/FP</th>
                  <th className="py-2 pr-3">Fusion FDR</th>
                </tr>
              </thead>
              <tbody>
                {ev.conditions.map(c => (
                  <tr key={`${c.key}-${c.conditionValue}`} className="border-b last:border-0">
                    <td className="py-2 pr-3">{c.key}</td>
                    <td className="py-2 pr-3">{c.conditionValue}</td>
                    <td className="py-2 pr-3">{c.sampleCount}</td>
                    <td className="py-2 pr-3 tabular-nums">{c.baseline.confusion.truePositives} / {c.baseline.confusion.falsePositives}</td>
                    <td className="py-2 pr-3 tabular-nums">{fmtPct(c.baseline.metrics.fdr)}</td>
                    <td className="py-2 pr-3 tabular-nums">{c.fusion.confusion.truePositives} / {c.fusion.confusion.falsePositives}</td>
                    <td className="py-2 pr-3 tabular-nums">{fmtPct(c.fusion.metrics.fdr)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <Card className="p-4">
        <div className="flex items-center gap-2">
          <ListChecks size={16} className="text-muted-foreground" />
          <h4 className="text-sm font-semibold">Review agreement</h4>
        </div>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-xs text-muted-foreground">
                <th className="py-2 pr-3">Sample</th>
                <th className="py-2 pr-3">Scenario</th>
                <th className="py-2 pr-3">Reviews</th>
                <th className="py-2 pr-3">Resolved label</th>
                <th className="py-2 pr-3">State</th>
              </tr>
            </thead>
            <tbody>
              {ev.reviewAgreement.map(r => (
                <tr key={r.runSampleId} className="border-b last:border-0">
                  <td className="py-2 pr-3 font-mono text-xs">{r.runSampleId.slice(0, 13)}…</td>
                  <td className="py-2 pr-3">{r.scenario}</td>
                  <td className="py-2 pr-3">{r.reviewCount}</td>
                  <td className="py-2 pr-3">{r.resolvedLabel ?? '—'}</td>
                  <td className="py-2 pr-3">
                    <span className={`rounded-full px-2 py-0.5 text-xs ${AGREEMENT_STATE_TONE[r.agreementState] ?? 'bg-muted text-muted-foreground'}`}>
                      {r.agreementState}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}

const AGREEMENT_STATE_TONE: Record<string, string> = {
  AGREED: 'bg-emerald-500/15 text-emerald-600',
  RESOLVED: 'bg-primary/15 text-primary',
  TIED: 'bg-amber-500/15 text-amber-600',
  UNREVIEWED: 'bg-muted text-muted-foreground',
}

function ConfusionCard({ title, result }: { title: string; result: ResearchEvaluatorResult }) {
  const c = result.confusion
  const m = result.metrics
  return (
    <Card className="p-4">
      <div className="flex items-center gap-2">
        <Gauge size={16} className="text-muted-foreground" />
        <h4 className="text-sm font-semibold">{title} <span className="font-normal text-muted-foreground">v{result.version}</span></h4>
      </div>
      <div className="mt-3 grid grid-cols-[auto_1fr_1fr] overflow-hidden rounded-xl border text-center text-xs">
        <div className="bg-muted/50 px-3 py-2 text-left">Predicted →</div>
        <div className="bg-muted/50 px-3 py-2">Positive</div>
        <div className="bg-muted/50 px-3 py-2">Negative</div>
        <div className="bg-muted/50 px-3 py-2 text-left">Actual positive</div>
        <div className="bg-emerald-500/10 px-3 py-2 font-semibold tabular-nums">{c.truePositives}</div>
        <div className="bg-muted/30 px-3 py-2 tabular-nums">{c.falseNegatives}</div>
        <div className="bg-muted/50 px-3 py-2 text-left">Actual negative</div>
        <div className="bg-rose-500/10 px-3 py-2 font-semibold tabular-nums">{c.falsePositives}</div>
        <div className="bg-muted/30 px-3 py-2 tabular-nums">{c.trueNegatives}</div>
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs sm:grid-cols-3">
        <MetricTerm label="Precision" v={m.precision} />
        <MetricTerm label="Recall" v={m.recall} />
        <MetricTerm label="Specificity" v={m.specificity} />
        <MetricTerm label="Accuracy" v={m.accuracy} />
        <MetricTerm label="False positive rate" v={m.falsePositiveRate} />
        <MetricTerm label="False negative rate" v={m.falseNegativeRate} />
        <MetricTerm label="F1" v={m.f1} />
        <MetricTerm label="Wrongful warning rate" v={m.wrongfulWarningRate} />
        <MetricTerm label="FDR" v={m.fdr} />
      </dl>
    </Card>
  )
}

function MetricTerm({ label, v }: { label: string; v: number | null }) {
  return (
    <div className="flex items-baseline justify-between gap-2 border-b border-muted pb-1">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium tabular-nums">{fmtPct(v)}</dd>
    </div>
  )
}

function fmtPct(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

function fmtBytes(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  if (value >= 1_048_576) return `${(value / 1_048_576).toFixed(1)} MB`
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${value.toFixed(0)} B`
}
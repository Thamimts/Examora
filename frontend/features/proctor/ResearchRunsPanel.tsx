import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BadgeCheck, CheckCircle2, Eye, FlaskConical, ListChecks, Play, Plus, Save, Square, XCircle } from 'lucide-react'
import { Card } from '@/components/shared'
import { useToast } from '@/components/feedback'
import { researchApi } from '@/services/researchApi'
import { RESEARCH_SCENARIOS, RESEARCH_SCENARIO_LABELS, type ResearchRun, type ResearchRunSample, type ResearchScenario } from '@/types/research'

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
    mutationFn: ({ sample, label }: { sample: ResearchRunSample; label: string }) =>
      researchApi.addReview(sample.researchSampleId!, { label }),
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
                      <th className="py-2 pr-3">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.samples.map(sample => (
                      <tr key={sample.id} className="border-b last:border-0">
                        <td className="py-2 pr-3 font-mono text-xs">{sample.attemptId.slice(0, 13)}…</td>
                        <td className="py-2 pr-3">{sample.scenario}</td>
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
                            {sample.status === 'PLANNED' && detail.run.status === 'RUNNING' && (
                              <button onClick={() => observeMutation.mutate(sample)}
                                className="flex items-center gap-1 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground">
                                <Play size={12} /> Observe
                              </button>
                            )}
                            {sample.status === 'CAPTURING' && detail.run.status === 'RUNNING' && (
                              <button onClick={() => captureMutation.mutate(sample)}
                                disabled={!canCaptureNow(sample)}
                                className="flex items-center gap-1 rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50">
                                <BadgeCheck size={12} /> Capture
                              </button>
                            )}
                            {sample.status === 'CAPTURED' && (
                              <>
                                {sample.researchSampleId && !sample.reviewed && (
                                  <div className="flex items-center gap-1">
                                    <button onClick={() => reviewMutation.mutate({ sample, label: 'ANOMALY' })}
                                      className="rounded-lg border px-2 py-1 text-xs text-rose-600">
                                      Anomaly
                                    </button>
                                    <button onClick={() => reviewMutation.mutate({ sample, label: 'NORMAL' })}
                                      className="rounded-lg border px-2 py-1 text-xs text-emerald-600">
                                      Normal
                                    </button>
                                  </div>
                                )}
                                {sample.reviewed && (
                                  <span className="text-xs text-muted-foreground">
                                    {sample.evaluated ? 'evaluable' : sample.tied ? 'tied' : 'reviewed'}
                                  </span>
                                )}
                                {!sample.researchSampleId && (
                                  <span className="text-xs text-muted-foreground">no linked sample</span>
                                )}
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
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
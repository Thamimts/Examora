import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BarChart3, Clock3, Database, FlaskConical, Gauge, Play, Save, ShieldCheck, Trash2 } from 'lucide-react'
import { Card, Header } from '@/components/shared'
import { useToast } from '@/components/feedback'
import { researchApi } from '@/services/researchApi'
import { ResearchRunsPanel } from './ResearchRunsPanel'
import type { ResearchConditionKey, ResearchEvaluatorResult } from '@/types/research'

const CONDITION_KEYS: { key: ResearchConditionKey; label: string }[] = [
  { key: 'lighting', label: 'Lighting' },
  { key: 'cameraQuality', label: 'Camera quality' },
  { key: 'network', label: 'Network' },
  { key: 'cameraAngle', label: 'Camera angle' },
]

function fmt(value: number | null | undefined, digits = 3): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return value.toFixed(digits)
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

function EvaluatorPanel({ title, tone, result }: { title: string; tone: 'baseline' | 'fusion'; result: ResearchEvaluatorResult }) {
  const c = result.confusion
  const m = result.metrics
  const accent = tone === 'fusion' ? 'border-primary/40' : 'border-border'
  return (
    <Card className={`p-4 ${accent}`}>
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">{title}</h3>
        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">{result.version}</span>
      </div>
      <div className="mt-3 grid grid-cols-4 gap-2 text-center">
        <div className="rounded-lg bg-muted p-2">
          <p className="text-xs text-muted-foreground">TP</p>
          <p className="text-sm font-semibold">{c.truePositives}</p>
        </div>
        <div className="rounded-lg bg-muted p-2">
          <p className="text-xs text-muted-foreground">TN</p>
          <p className="text-sm font-semibold">{c.trueNegatives}</p>
        </div>
        <div className="rounded-lg bg-muted p-2">
          <p className="text-xs text-muted-foreground">FP</p>
          <p className="text-sm font-semibold">{c.falsePositives}</p>
        </div>
        <div className="rounded-lg bg-muted p-2">
          <p className="text-xs text-muted-foreground">FN</p>
          <p className="text-sm font-semibold">{c.falseNegatives}</p>
        </div>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <Metric label="Precision" value={fmt(m.precision)} />
        <Metric label="Recall" value={fmt(m.recall)} />
        <Metric label="F1" value={fmt(m.f1)} />
        <Metric label="Accuracy" value={fmt(m.accuracy)} />
        <Metric label="False positive rate" value={fmt(m.falsePositiveRate)} />
        <Metric label="False negative rate" value={fmt(m.falseNegativeRate)} />
      </div>
      <p className="mt-3 text-xs text-muted-foreground">
        Wrongful warning rate (share of positive predictions that were wrong): {fmt(m.wrongfulWarningRate)}
      </p>
    </Card>
  )
}

export function ProctoringResearch() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [experimentId, setExperimentId] = useState<string>('')
  const [condition, setCondition] = useState<ResearchConditionKey | ''>('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const experimentsQuery = useQuery({
    queryKey: ['research-experiments'],
    queryFn: researchApi.listExperiments,
    select: data => data.data.data,
  })

  const evaluationQuery = useQuery({
    queryKey: ['research-evaluation', experimentId, condition],
    queryFn: () => researchApi.evaluate(experimentId, condition === '' ? undefined : condition),
    select: data => data.data.data,
    enabled: experimentId.length > 0,
  })

  const createMutation = useMutation({
    mutationFn: researchApi.createExperiment,
    onSuccess: () => {
      toast?.success('Experiment created')
      queryClient.invalidateQueries({ queryKey: ['research-experiments'] })
      setName('')
      setDescription('')
    },
    onError: () => toast?.error('Unable to create experiment'),
  })

  const runMutation = useMutation({
    mutationFn: researchApi.runEvaluation,
    onSuccess: () => {
      toast?.success('Evaluation refreshed')
      queryClient.invalidateQueries({ queryKey: ['research-evaluation', experimentId, condition] })
    },
    onError: () => toast?.error('Unable to run evaluation'),
  })

  const experiments = useMemo(() => experimentsQuery.data ?? [], [experimentsQuery.data])
  const evaluation = evaluationQuery.data
  const selected = experiments.find(e => e.id === experimentId) ?? null

  return (
    <>
      <Header
        title="Proctoring research"
        description="Experimental evaluation of the binary baseline vs fusion-v1. All data is staged, experimental and consented."
      />
      <div className="flex flex-wrap items-center gap-3">
        <select
          value={experimentId}
          onChange={e => setExperimentId(e.target.value)}
          className="field min-w-64"
        >
          <option value="">Select an experiment…</option>
          {experiments.map(e => (
            <option key={e.id} value={e.id}>{e.name} · {e.status}</option>
          ))}
        </select>
        <select
          value={condition}
          onChange={e => setCondition(e.target.value as ResearchConditionKey | '')}
          className="field min-w-48"
          disabled={!experimentId}
        >
          <option value="">All conditions</option>
          {CONDITION_KEYS.map(c => (
            <option key={c.key} value={c.key}>{c.label}</option>
          ))}
        </select>
        {experimentsQuery.isLoading && <p className="text-sm text-muted-foreground">Loading experiments…</p>}
      </div>

      <Card className="mt-4">
        <div className="flex items-center gap-2">
          <Gauge size={16} className="text-muted-foreground" />
          <h3 className="text-sm font-semibold">New experiment</h3>
        </div>
        <form
          className="mt-3 flex flex-wrap items-end gap-3"
          onSubmit={e => {
            e.preventDefault()
            if (name.trim()) createMutation.mutate({ name: name.trim(), description: description.trim() || undefined })
          }}
        >
          <label className="block min-w-56 text-sm font-medium">
            Name
            <input className="field mt-1" value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Fusion comparison study" />
          </label>
          <label className="block min-w-72 flex-1 text-sm font-medium">
            Description
            <input className="field mt-1" value={description} onChange={e => setDescription(e.target.value)} placeholder="Optional context" />
          </label>
          <button
            disabled={createMutation.isPending || name.trim().length === 0}
            className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60"
          >
            <Save size={15} /> {createMutation.isPending ? 'Creating…' : 'Create'}
          </button>
        </form>
      </Card>

      <div className="mt-4 space-y-4">
        {selected && (
          <>
            <Card className="p-4">
              <div className="flex flex-wrap items-center gap-3">
                <div className="min-w-48">
                  <p className="text-xs text-muted-foreground">Versions</p>
                  <div className="mt-1 flex flex-wrap items-center gap-1">
                    <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                      {selected.datasetVersion}
                    </span>
                    <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                      {selected.algorithmVersion} vs {selected.baselineVersion}
                    </span>
                    <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                      {selected.status}
                    </span>
                  </div>
                  {evaluation && (
                    <p className="mt-1 text-xs text-muted-foreground">
                      Evaluated {new Date(evaluation.evaluatedAt).toLocaleString()}
                    </p>
                  )}
                </div>
                <div className="grid flex-1 grid-cols-2 gap-2 sm:grid-cols-4">
                  <Metric label="Total samples" value={String(evaluation?.totalSamples ?? 0)} />
                  <Metric label="Evaluated" value={String(evaluation?.evaluatedSamples ?? 0)} />
                  <Metric label="Human-reviewed" value={String(evaluation?.reviewedSamples ?? 0)} />
                  <Metric label="Unevaluable" value={String(evaluation?.unevaluatedSamples ?? 0)} />
                </div>
                <button
                  onClick={() => runMutation.mutate(experimentId)}
                  disabled={runMutation.isPending || !selected}
                  className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-60"
                >
                  <Play size={15} /> {runMutation.isPending ? 'Running…' : 'Evaluate'}
                </button>
              </div>
              {evaluation && evaluation.scenarios.length > 0 && (
                <div className="mt-3 border-t pt-3">
                  <p className="text-xs text-muted-foreground">Scenario distribution (controlled dataset)</p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {evaluation.scenarios.map(s => (
                      <span key={s.scenario} className="rounded-full bg-muted px-2 py-0.5 text-xs">
                        {s.scenario} · {s.sampleCount}
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </Card>

            <ResearchRunsPanel experimentId={experimentId} />
          </>
        )}

        {evaluation && (evaluation.evaluatedSamples > 0 ? (
          <>
            <div className="grid gap-4 lg:grid-cols-2">
              <EvaluatorPanel title="Binary baseline" tone="baseline" result={evaluation.baseline} />
              <EvaluatorPanel title="Confidence-aware fusion" tone="fusion" result={evaluation.fusion} />
            </div>

            <Card className="p-4">
              <div className="flex items-center gap-2">
                <ShieldCheck size={16} className="text-muted-foreground" />
                <h3 className="text-sm font-semibold">Data quality & ground truth</h3>
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                Ground truth is the resolved human-review majority; ties and invalid windows are never silently
                converted or discarded, and counts below are derived from stored research rows.
              </p>
              <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-5">
                <Metric label="Registered" value={String(evaluation.dataQuality.registered)} />
                <Metric label="Evaluable" value={String(evaluation.dataQuality.evaluable)} />
                <Metric label="Unreviewed" value={String(evaluation.dataQuality.unreviewed)} />
                <Metric label="Tied" value={String(evaluation.dataQuality.tied)} />
                <Metric label="Invalid" value={String(evaluation.dataQuality.invalid)} />
                <Metric label="Scenario agreement" value={String(evaluation.dataQuality.scenarioGroundTruthAgreement)} />
                <Metric label="Missing signals" value={String(evaluation.dataQuality.missingSignals)} />
                <Metric label="Missing condition" value={String(evaluation.dataQuality.missingCondition)} />
                <Metric label="Missing latency" value={String(evaluation.dataQuality.missingMeasuredLatency)} />
                <Metric label="Missing bandwidth" value={String(evaluation.dataQuality.missingBandwidth)} />
              </div>
              {evaluation.scenarios.length > 0 && (
                <div className="mt-3 overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-xs text-muted-foreground">
                        <th className="py-2 pr-3">Scenario</th>
                        <th className="py-2 pr-3">Expected label</th>
                        <th className="py-2 pr-3">Samples</th>
                        <th className="py-2 pr-3">Resolved</th>
                        <th className="py-2 pr-3">Agree</th>
                      </tr>
                    </thead>
                    <tbody>
                      {evaluation.scenarios.map(s => (
                        <tr key={s.scenario} className="border-b last:border-0">
                          <td className="py-2 pr-3 font-medium">{s.scenario}{s.label ? ` — ${s.label}` : ''}</td>
                          <td className="py-2 pr-3">{s.expectedLabel}</td>
                          <td className="py-2 pr-3">{s.sampleCount}</td>
                          <td className="py-2 pr-3">{s.resolvedCount}</td>
                          <td className="py-2 pr-3">{s.agreementCount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            <div className="grid gap-4 lg:grid-cols-2">
              <Card className="p-4">
                <div className="flex items-center gap-2">
                  <Clock3 size={16} className="text-muted-foreground" />
                  <h3 className="text-sm font-semibold">Detection latency</h3>
                </div>
                <p className="mt-2 text-xs text-muted-foreground">
                  First in-window signal occurrence minus window start, from stored signals.
                </p>
                <div className="mt-3 grid grid-cols-2 gap-2">
                  <Metric label="Measured samples" value={String(evaluation.latency.measuredCount)} />
                  <Metric label="Mean" value={fmtMs(evaluation.latency.meanMs)} />
                  <Metric label="Median" value={fmtMs(evaluation.latency.medianMs)} />
                  <Metric label="Min / Max" value={`${fmtMs(evaluation.latency.minMs)} / ${fmtMs(evaluation.latency.maxMs)}`} />
                </div>
              </Card>

              <Card className="p-4">
                <div className="flex items-center gap-2">
                  <Database size={16} className="text-muted-foreground" />
                  <h3 className="text-sm font-semibold">Bandwidth & privacy</h3>
                </div>
                <p className="mt-2 text-xs text-muted-foreground">
                  Data-minimization ratio 1 − signal/raw media bytes; shown only when raw media is actually measured.
                </p>
                <div className="mt-3 grid grid-cols-2 gap-2">
                  <Metric label="Measured samples" value={String(evaluation.bandwidth.measuredCount)} />
                  <Metric label="Mean minimization" value={fmt(evaluation.bandwidth.meanDataMinimizationRatio)} />
                  <Metric label="Mean raw bytes" value={fmt(evaluation.bandwidth.meanRawMediaBytes, 0)} />
                  <Metric label="Mean signal bytes" value={fmt(evaluation.bandwidth.meanSignalBytes, 0)} />
                </div>
              </Card>
            </div>

            {condition && evaluation.conditions && evaluation.conditions.length > 0 && (
              <Card className="p-4">
                <div className="flex items-center gap-2">
                  <BarChart3 size={16} className="text-muted-foreground" />
                  <h3 className="text-sm font-semibold">Condition breakdown</h3>
                </div>
                <div className="mt-3 overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-xs text-muted-foreground">
                        <th className="py-2 pr-3">Condition</th>
                        <th className="py-2 pr-3">Samples</th>
                        <th className="py-2 pr-3">Baseline precision</th>
                        <th className="py-2 pr-3">Baseline recall</th>
                        <th className="py-2 pr-3">Fusion precision</th>
                        <th className="py-2 pr-3">Fusion recall</th>
                      </tr>
                    </thead>
                    <tbody>
                      {evaluation.conditions.map(row => (
                        <tr key={row.conditionValue} className="border-b last:border-0">
                          <td className="py-2 pr-3 font-medium">{row.conditionValue}</td>
                          <td className="py-2 pr-3">{row.sampleCount}</td>
                          <td className="py-2 pr-3">{fmt(row.baseline.metrics.precision)}</td>
                          <td className="py-2 pr-3">{fmt(row.baseline.metrics.recall)}</td>
                          <td className="py-2 pr-3">{fmt(row.fusion.metrics.precision)}</td>
                          <td className="py-2 pr-3">{fmt(row.fusion.metrics.recall)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>
            )}
          </>
        ) : (
          <Card className="flex flex-col items-center gap-2 p-8 text-center">
            <FlaskConical size={28} className="text-muted-foreground" />
            <p className="text-sm font-medium">No evaluated samples yet.</p>
            <p className="max-w-md text-xs text-muted-foreground">
              Created samples only enter evaluation once they have at least one human review with a resolved
              label (no reviewer tie). No results or metrics are fabricated.
            </p>
          </Card>
        ))}

        {!experimentId && !experimentsQuery.isLoading && (
          <Card className="flex items-center gap-2 p-8 text-center text-sm text-muted-foreground">
            <Trash2 size={16} /> Select an experiment to view its evaluation.
          </Card>
        )}
      </div>
    </>
  )
}
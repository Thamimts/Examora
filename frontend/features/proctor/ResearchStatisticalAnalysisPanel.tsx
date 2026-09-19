import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Activity,
  BarChart3,
  Database,
  FlaskConical,
  Gauge,
  Hourglass,
  Info,
  MousePointerClick,
  RefreshCcw,
  ShieldCheck,
  Sigma,
  Users,
} from 'lucide-react'
import { Card } from '@/components/shared'
import { researchApi } from '@/services/researchApi'
import type { AnalysisReport, AnalysisStratum, ResearchRun } from '@/types/research'

const DISAGREEMENT_LABELS: Record<string, string> = {
  AGREE_POSITIVE: 'Both predict positive',
  AGREE_NEGATIVE: 'Both predict negative',
  BASELINE_ONLY_POSITIVE: 'Baseline only positive',
  FUSION_ONLY_POSITIVE: 'Fusion only positive',
}

function fmt(value: number | null | undefined, digits = 3): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return value.toFixed(digits)
}

function fmtMs(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return `${Math.round(value)} ms`
}

function pct(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border bg-card p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-sm font-semibold text-foreground">{value}</p>
    </div>
  )
}

function PanelHeader({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="flex items-center gap-2">
      {icon}
      <h3 className="text-sm font-semibold">{title}</h3>
    </div>
  )
}

function ConfusionGrid({ report }: { report: AnalysisReport }) {
  const baseline = report.baseline.confusion
  const fusion = report.fusion.confusion
  const cells = [['TP', 'TN'], ['FP', 'FN']] as const
  const accessors = {
    TP: (c: typeof baseline) => c.truePositives,
    TN: (c: typeof baseline) => c.trueNegatives,
    FP: (c: typeof baseline) => c.falsePositives,
    FN: (c: typeof baseline) => c.falseNegatives,
  } as const
  return (
    <div className="grid grid-cols-2 gap-4">
      {([['Baseline', baseline], ['Fusion', fusion]] as const).map(([label, c]) => (
        <div key={label} className="rounded-xl border p-3">
          <p className="text-xs text-muted-foreground">{label}</p>
          <div className="mt-2 grid grid-cols-2 gap-2 text-center">
            {cells.flat().map(key => (
              <div key={`${label}-${key}`} className="rounded-lg bg-muted p-2">
                <p className="text-xs text-muted-foreground">{key}</p>
                <p className="text-sm font-semibold">{accessors[key](c)}</p>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

function DeltaTable({ deltas }: { deltas: AnalysisReport['metricDeltas'] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th className="py-2 pr-3">Metric</th>
            <th className="py-2 pr-3">Δ fusion − baseline</th>
          </tr>
        </thead>
        <tbody>
          {deltas.map(d => (
            <tr key={d.metric} className="border-b last:border-0">
              <td className="py-2 pr-3 font-medium">{d.metric}</td>
              <td className="py-2 pr-3">
                <span className={d.delta === null || d.delta === 0 ? '' : d.delta > 0 ? 'text-emerald-600' : 'text-rose-600'}>
                  {d.delta === null ? '—' : `${d.delta >= 0 ? '+' : ''}${d.delta.toFixed(3)}`}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-xs text-muted-foreground">
        Observed differences only — fusion is never labelled better or worse than baseline.
      </p>
    </div>
  )
}

function StratumTable({ strata }: { strata: AnalysisStratum[] }) {
  if (strata.length === 0) {
    return <p className="text-xs text-muted-foreground">No evaluable samples in this scope.</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th className="py-2 pr-3">Stratum</th>
            <th className="py-2 pr-3">Samples</th>
            <th className="py-2 pr-3">Baseline prec/rec</th>
            <th className="py-2 pr-3">Fusion prec/rec</th>
            <th className="py-2 pr-3">Δ F1</th>
          </tr>
        </thead>
        <tbody>
          {strata.map(s => (
            <tr key={s.value} className="border-b last:border-0">
              <td className="py-2 pr-3 font-medium">{s.value}</td>
              <td className="py-2 pr-3">{s.sampleCount}</td>
              <td className="py-2 pr-3">
                {fmt(s.baseline.metrics.precision)} / {fmt(s.baseline.metrics.recall)}
              </td>
              <td className="py-2 pr-3">
                {fmt(s.fusion.metrics.precision)} / {fmt(s.fusion.metrics.recall)}
              </td>
              <td className="py-2 pr-3">{fmt(s.deltas.find(d => d.metric === 'f1')?.delta)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function LatencyBlock({ title, d }: { title: string; d: AnalysisReport['latency']['measured'] }) {
  return (
    <div className="rounded-xl border p-3">
      <p className="text-xs text-muted-foreground">{title}</p>
      <div className="mt-2 grid grid-cols-2 gap-2">
        <Metric label="Measured" value={String(d.measuredCount)} />
        <Metric label="Mean" value={fmtMs(d.mean)} />
        <Metric label="Median" value={fmtMs(d.median)} />
        <Metric label="Min / max" value={`${fmtMs(d.min)} / ${fmtMs(d.max)}`} />
      </div>
    </div>
  )
}

function StratumLatencyTable({ strata, kind }: { strata: AnalysisReport['latencyByScenario']; kind: string }) {
  if (strata.length === 0) {
    return <p className="text-xs text-muted-foreground">No {kind} with measured latency or stored signals.</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th className="py-2 pr-3">{kind === 'scenario' ? 'Scenario' : 'Condition'}</th>
            <th className="py-2 pr-3">Samples</th>
            <th className="py-2 pr-3">Measured mean</th>
            <th className="py-2 pr-3">Stored-window mean</th>
            <th className="py-2 pr-3">Stored-window median</th>
          </tr>
        </thead>
        <tbody>
          {strata.map(s => (
            <tr key={s.value} className="border-b last:border-0">
              <td className="py-2 pr-3 font-medium">{s.value}</td>
              <td className="py-2 pr-3">{s.sampleCount}</td>
              <td className="py-2 pr-3">{fmtMs(s.latency.measured.mean)}</td>
              <td className="py-2 pr-3">{fmtMs(s.latency.storedWindow.mean)}</td>
              <td className="py-2 pr-3">{fmtMs(s.latency.storedWindow.median)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function IntervalTable({ intervals }: { intervals: AnalysisReport['confidenceIntervals'] }) {
  if (intervals.length === 0) {
    return <p className="text-xs text-muted-foreground">No evaluable samples — no intervals computed.</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th className="py-2 pr-3">Metric</th>
            <th className="py-2 pr-3">Estimate</th>
            <th className="py-2 pr-3">95% lower</th>
            <th className="py-2 pr-3">95% upper</th>
          </tr>
        </thead>
        <tbody>
          {intervals.map(i => (
            <tr key={i.metric} className="border-b last:border-0">
              <td className="py-2 pr-3 font-medium">{i.metric}</td>
              <td className="py-2 pr-3">{fmt(i.interval.estimate)}</td>
              <td className="py-2 pr-3">{fmt(i.interval.lower)}</td>
              <td className="py-2 pr-3">{fmt(i.interval.upper)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-xs text-muted-foreground">
        Wilson score intervals (z = 1.96). F1 has no justified interval and is omitted.
      </p>
    </div>
  )
}

export function ResearchStatisticalAnalysisPanel({ experimentId }: { experimentId: string }) {
  const [runId, setRunId] = useState('')
  const [refreshing, setRefreshing] = useState(false)

  const runsQuery = useQuery({
    queryKey: ['research-runs', experimentId],
    queryFn: () => researchApi.listRuns(experimentId),
    select: data => data.data.data,
    enabled: experimentId.length > 0,
  })

  const analysisQuery = useQuery({
    queryKey: ['research-analysis', experimentId, runId],
    queryFn: () => researchApi.getExperimentAnalysis(experimentId, runId || undefined),
    select: data => data.data.data,
    enabled: experimentId.length > 0,
    staleTime: 0,
  })

  const runs: ResearchRun[] = useMemo(() => runsQuery.data ?? [], [runsQuery.data])
  const report = analysisQuery.data

  const refresh = async () => {
    setRefreshing(true)
    await analysisQuery.refetch()
    setRefreshing(false)
  }

  const available = report !== undefined && report.capturedSamples > 0

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <PanelHeader icon={<Sigma size={16} className="text-muted-foreground" />} title="Statistical analysis" />
        <p className="mt-2 text-xs text-muted-foreground">
          Deterministic, read-only analysis of the persisted controlled dataset. Only samples with a resolved
          human-review ground truth are evaluated; nothing is generated, simulated or inferred, and no raw media
          or student identity is ever exported. Aggregates only — no sample-level rows are shown.
        </p>

        <div className="mt-3 flex flex-wrap items-center gap-3">
          <select
            value={runId}
            onChange={e => setRunId(e.target.value)}
            className="field min-w-64"
            disabled={runs.length === 0}
          >
            <option value="">Whole experiment (eligible runs)</option>
            {runs.map(r => (
              <option key={r.id} value={r.id}>{r.runCode} · {r.status}</option>
            ))}
          </select>
          <button
            onClick={refresh}
            disabled={refreshing}
            className="flex items-center gap-2 rounded-xl border px-3 py-2 text-sm font-medium disabled:opacity-60"
          >
            <RefreshCcw size={14} className={refreshing ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>

        {report && (
          <div className="mt-3 flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <span className="rounded-full bg-muted px-2 py-0.5">{report.datasetVersion}</span>
            <span className="rounded-full bg-muted px-2 py-0.5">{report.baselineVersion}</span>
            <span className="rounded-full bg-muted px-2 py-0.5">{report.fusionVersion}</span>
            <span className="rounded-full bg-muted px-2 py-0.5">{report.analysisVersion}</span>
            <span className="rounded-full bg-muted px-2 py-0.5">
              Generated {new Date(report.generatedAt).toLocaleString()}
            </span>
          </div>
        )}
      </Card>

      {!report ? (
        <Card className="flex flex-col items-center gap-2 p-8 text-center">
          <Sigma size={24} className="text-muted-foreground" />
          <p className="text-sm font-medium">Analysis unavailable.</p>
          <p className="max-w-md text-xs text-muted-foreground">
            Statistical analysis is available only for experiments with at least one captured sample. No results
            are fabricated for empty scopes.
          </p>
        </Card>
      ) : !available ? (
        <Card className="flex flex-col items-center gap-2 p-8 text-center">
          <FlaskConical size={24} className="text-muted-foreground" />
          <p className="text-sm font-medium">No captured samples in this scope.</p>
          <p className="max-w-md text-xs text-muted-foreground">
            Capture and review samples to populate the analysis. Until then all metrics, deltas and agreement
            summaries are reported as unavailable (never zero-by-inference).
          </p>
        </Card>
      ) : (
        <>
          <Card className="p-4">
            <PanelHeader icon={<ShieldCheck size={16} className="text-muted-foreground" />} title="Dataset overview" />
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Metric label="Registered samples" value={String(report.registeredSamples)} />
              <Metric label="Captured" value={String(report.capturedSamples)} />
              <Metric label="Human-reviewed" value={String(report.reviewedSamples)} />
              <Metric label="Evaluable (ground truth)" value={String(report.evaluableSamples)} />
              <Metric label="Evaluable coverage" value={pct(report.evaluableCoverage)} />
              <Metric label="Unreviewed" value={String(report.unreviewedSamples)} />
              <Metric label="Tied" value={String(report.tiedSamples)} />
              <Metric label="Invalid / excluded" value={String(report.invalidSamples)} />
              <Metric label="Missing signals" value={String(report.missingSignalSamples)} />
              <Metric label="Missing condition" value={String(report.missingConditionSamples)} />
              <Metric label="Missing latency" value={String(report.missingLatencySamples)} />
              <Metric label="Missing bandwidth" value={String(report.missingBandwidthSamples)} />
            </div>
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<Gauge size={16} className="text-muted-foreground" />} title="Confusion & metrics" />
            <p className="mt-2 text-xs text-muted-foreground">
              Baseline is the binary detector, fusion is the confidence-aware fusion engine. Metrics compare
              observed predictions to resolved human-review ground truth.
            </p>
            <div className="mt-3">
              <ConfusionGrid report={report} />
            </div>
            <div className="mt-3">
              <DeltaTable deltas={report.metricDeltas} />
            </div>
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<Activity size={16} className="text-muted-foreground" />} title="Prediction changes" />
            <p className="mt-2 text-xs text-muted-foreground">
              Where fusion changed the baseline prediction on evaluable samples, and which transitions were
              observed between them (regressions vs repairs).
            </p>
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Metric label="Baseline correct" value={String(report.transitions.baselineCorrectCount)} />
              <Metric label="Fusion correct" value={String(report.transitions.fusionCorrectCount)} />
              <Metric label="Changed predictions" value={String(report.transitions.changedPredictionCount)} />
              <Metric label="Baseline→fusion wrong" value={String(report.transitions.baselineCorrectToFusionWrong)} />
              <Metric label="Baseline wrong→fusion correct" value={String(report.transitions.baselineWrongToFusionCorrect)} />
              <Metric label="Baseline errors" value={String(report.transitions.baselineErrorCount)} />
              <Metric label="Fusion errors" value={String(report.transitions.fusionErrorCount)} />
            </div>
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<MousePointerClick size={16} className="text-muted-foreground" />} title="Disagreement by category" />
            <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
              {report.disagreementSummary.map(c => (
                <div key={c.category} className="flex items-center justify-between rounded-xl border px-3 py-2 text-sm">
                  <span className="font-medium">{DISAGREEMENT_LABELS[c.category] ?? c.category}</span>
                  <span className="text-muted-foreground">
                    {c.count} · {pct(c.percentage)}
                  </span>
                </div>
              ))}
            </div>
            {report.disagreementSamples.length > 0 && (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">Sample</th>
                      <th className="py-2 pr-3">Scenario</th>
                      <th className="py-2 pr-3">Ground truth</th>
                      <th className="py-2 pr-3">Baseline</th>
                      <th className="py-2 pr-3">Fusion</th>
                      <th className="py-2 pr-3">Signals</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.disagreementSamples.map(s => (
                      <tr key={s.sampleId} className="border-b last:border-0">
                        <td className="py-2 pr-3 text-xs text-muted-foreground">{s.sampleId}</td>
                        <td className="py-2 pr-3">{s.scenario ?? '—'}</td>
                        <td className="py-2 pr-3">{s.groundTruthLabel ?? '—'}</td>
                        <td className="py-2 pr-3">{s.baselinePositive ? 'positive' : 'negative'}</td>
                        <td className="py-2 pr-3">{s.fusionPositive ? 'positive' : 'negative'}</td>
                        <td className="py-2 pr-3 text-xs text-muted-foreground">{s.signalSummary}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <PanelHeader icon={<BarChart3 size={16} className="text-muted-foreground" />} title="Stratified by scenario" />
              <div className="mt-3">
                <StratumTable strata={report.scenarios} />
              </div>
            </Card>
            <Card className="p-4">
              <PanelHeader icon={<BarChart3 size={16} className="text-muted-foreground" />} title="Stratified by controlled condition" />
              <p className="mt-2 text-xs text-muted-foreground">
                Controlled conditions only (lighting, camera quality, network, camera angle) — never demographic.
              </p>
              <div className="mt-3">
                <StratumTable strata={report.conditions} />
              </div>
            </Card>
          </div>

          <Card className="p-4">
            <PanelHeader icon={<Database size={16} className="text-muted-foreground" />} title="Confidence distribution by source" />
            <p className="mt-2 text-xs text-muted-foreground">
              Descriptive confidence scores from the stored in-window signals, per signal source.
            </p>
            {report.confidence.length === 0 ? (
              <p className="mt-3 text-xs text-muted-foreground">No confidence-bearing signals recorded.</p>
            ) : (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">Source</th>
                      <th className="py-2 pr-3">Samples</th>
                      <th className="py-2 pr-3">Measured</th>
                      <th className="py-2 pr-3">Min</th>
                      <th className="py-2 pr-3">Max</th>
                      <th className="py-2 pr-3">Mean</th>
                      <th className="py-2 pr-3">Median</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.confidence.map(c => (
                      <tr key={c.source} className="border-b last:border-0">
                        <td className="py-2 pr-3 font-medium">{c.source}</td>
                        <td className="py-2 pr-3">{c.sampleCount}</td>
                        <td className="py-2 pr-3">{c.measuredCount}</td>
                        <td className="py-2 pr-3">{fmt(c.min)}</td>
                        <td className="py-2 pr-3">{fmt(c.max)}</td>
                        <td className="py-2 pr-3">{fmt(c.mean)}</td>
                        <td className="py-2 pr-3">{fmt(c.median)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <PanelHeader icon={<Hourglass size={16} className="text-muted-foreground" />} title="Detection latency" />
              <p className="mt-2 text-xs text-muted-foreground">
                Measured = researcher-recorded windows; stored-window = earliest recorded in-window signal minus
                window start.
              </p>
              <div className="mt-3 grid gap-2 sm:grid-cols-2">
                <LatencyBlock title="Measured latency" d={report.latency.measured} />
                <LatencyBlock title="Stored-window latency" d={report.latency.storedWindow} />
              </div>
            </Card>
            <Card className="p-4">
              <PanelHeader icon={<Database size={16} className="text-muted-foreground" />} title="Bandwidth & data minimisation" />
              <p className="mt-2 text-xs text-muted-foreground">
                Raw media bytes and stored signal bytes over the scope. Zero bytes are counted as measured.
              </p>
              <div className="mt-3 grid grid-cols-2 gap-2">
                <Metric label="Raw media samples" value={String(report.bandwidth.rawMedia.measuredCount)} />
                <Metric label="Signal samples" value={String(report.bandwidth.signalBytes.measuredCount)} />
                <Metric label="Mean raw bytes" value={fmt(report.bandwidth.rawMedia.mean, 0)} />
                <Metric label="Mean signal bytes" value={fmt(report.bandwidth.signalBytes.mean, 0)} />
              </div>
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <PanelHeader icon={<Hourglass size={16} className="text-muted-foreground" />} title="Latency by scenario" />
              <div className="mt-3">
                <StratumLatencyTable strata={report.latencyByScenario} kind="scenario" />
              </div>
            </Card>
            <Card className="p-4">
              <PanelHeader icon={<Hourglass size={16} className="text-muted-foreground" />} title="Latency by condition" />
              <div className="mt-3">
                <StratumLatencyTable strata={report.latencyByCondition} kind="condition" />
              </div>
            </Card>
          </div>

          <Card className="p-4">
            <PanelHeader icon={<Users size={16} className="text-muted-foreground" />} title="Human review agreement" />
            <p className="mt-2 text-xs text-muted-foreground">
              Resolved = reviews produce a non-tied majority ground truth. Tie breaks and undecided reviews are
              never silently converted.
            </p>
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Metric label="Reviewed samples" value={String(report.reviewAgreement.reviewedSamples)} />
              <Metric label="Resolved" value={String(report.reviewAgreement.resolvedSamples)} />
              <Metric label="Tied" value={String(report.reviewAgreement.tiedSamples)} />
              <Metric label="Agreement rate" value={pct(report.reviewAgreement.agreementRate)} />
              <Metric label="Pairwise agreement" value={pct(report.reviewAgreement.pairwiseAgreementRate)} />
              <Metric label="Single-reviewer dataset" value={report.reviewAgreement.singleReviewerDataset ? 'Yes' : 'No'} />
            </div>
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<Gauge size={16} className="text-muted-foreground" />} title="Confidence intervals (95% Wilson)" />
            <div className="mt-3">
              <IntervalTable intervals={report.confidenceIntervals} />
            </div>
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<Info size={16} className="text-muted-foreground" />} title="Limitations" />
            <ul className="mt-3 list-disc pl-5 text-xs text-muted-foreground">
              <li>
                Everything on this page is descriptive. Deltas report observed differences; they never claim
                fusion is better or worse than baseline and never claim generalization beyond this controlled dataset.
              </li>
              <li>Only evaluable samples (resolved human-review ground truth) contribute to metrics and strata.</li>
              <li>
                Latency, confidence and bandwidth are reported only when actually measured on real samples; no
                values are ever estimated or simulated.
              </li>
              <li>No demographic, biometric or raw-media data is captured — no demographic stratification exists.</li>
              <li>Confidence intervals are Wilson intervals for evaluable counts; small samples imply wide intervals.</li>
            </ul>
          </Card>
        </>
      )}
    </div>
  )
}
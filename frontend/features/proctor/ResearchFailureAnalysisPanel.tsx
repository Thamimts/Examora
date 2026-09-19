import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, BarChart3, Bug, ChevronLeft, ChevronRight, Fingerprint, Info, Layers, RefreshCcw, ShieldAlert, Shuffle } from 'lucide-react'
import { Card } from '@/components/shared'
import { researchApi } from '@/services/researchApi'
import type { FailureCase, FailureGroup, FailureReport, FailureSamplesPage, ResearchRun } from '@/types/research'

const CATEGORY_LABELS: Record<string, string> = {
  BASELINE_FALSE_POSITIVE: 'Baseline false positive',
  BASELINE_FALSE_NEGATIVE: 'Baseline false negative',
  FUSION_FALSE_POSITIVE: 'Fusion false positive',
  FUSION_FALSE_NEGATIVE: 'Fusion false negative',
  BASELINE_ONLY_POSITIVE: 'Baseline only positive',
  FUSION_ONLY_POSITIVE: 'Fusion only positive',
}

const TRANSITION_LABELS: Record<string, string> = {
  CORRECT_TO_CORRECT: 'Correct → correct',
  CORRECT_TO_WRONG: 'Correct → wrong',
  WRONG_TO_CORRECT: 'Wrong → correct',
  WRONG_TO_WRONG: 'Wrong → wrong',
}

const PAGE_SIZES = [25, 50, 100]

function fmt(value: number | null | undefined, digits = 3): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return value.toFixed(digits)
}

function pct(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(1)}%`
}

function rangeText(range: { min: number | null; max: number | null } | null): string {
  if (!range) return '—'
  return `${fmt(range.min, 2)} – ${fmt(range.max, 2)}`
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border bg-card p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-sm font-semibold text-foreground">{value}</p>
    </div>
  )
}

function PanelHeader({ icon, title }: { icon: ReactNode; title: string }) {
  return (
    <div className="flex items-center gap-2">
      {icon}
      <h3 className="text-sm font-semibold">{title}</h3>
    </div>
  )
}

function SmallSampleBadge() {
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-800"
      title="Fewer than 5 evaluable samples in this group — descriptive counts only, never generalized."
    >
      n &lt; 5
    </span>
  )
}

function GroupTable({ groups, kind }: { groups: FailureGroup[]; kind: 'scenario' | 'environment' }) {
  if (groups.length === 0) {
    return <p className="mt-3 text-xs text-muted-foreground">No {kind === 'scenario' ? 'scenario' : 'condition'} groups with evaluable samples in this scope.</p>
  }
  return (
    <div className="mt-3 overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th className="py-2 pr-3">{kind === 'scenario' ? 'Scenario' : 'Controlled condition'}</th>
            <th className="py-2 pr-3">Samples</th>
            <th className="py-2 pr-3">Baseline FP</th>
            <th className="py-2 pr-3">Baseline FN</th>
            <th className="py-2 pr-3">Fusion FP</th>
            <th className="py-2 pr-3">Fusion FN</th>
            <th className="py-2 pr-3">Fusion FP rate</th>
            <th className="py-2 pr-3">Fusion FN rate</th>
            <th className="py-2 pr-3">Disagreements</th>
          </tr>
        </thead>
        <tbody>
          {groups.map(g => (
            <tr key={g.value} className="border-b last:border-0">
              <td className="py-2 pr-3 font-medium">
                <span className="flex items-center gap-2">
                  {g.value}
                  {g.smallSample && <SmallSampleBadge />}
                </span>
              </td>
              <td className="py-2 pr-3">{g.sampleCount}</td>
              <td className="py-2 pr-3">{g.baselineFalsePositives}</td>
              <td className="py-2 pr-3">{g.baselineFalseNegatives}</td>
              <td className="py-2 pr-3">{g.fusionFalsePositives}</td>
              <td className="py-2 pr-3">{g.fusionFalseNegatives}</td>
              <td className="py-2 pr-3">{pct(g.fusionFalsePositiveRate)}</td>
              <td className="py-2 pr-3">{pct(g.fusionFalseNegativeRate)}</td>
              <td className="py-2 pr-3">{g.disagreementCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DistributionTable({ title, rows }: { title: string; rows: { value: string; count: number }[] }) {
  return (
    <div className="rounded-xl border p-3">
      <p className="text-xs font-medium text-foreground">{title}</p>
      {rows.length === 0 ? (
        <p className="mt-2 text-xs text-muted-foreground">None observed.</p>
      ) : (
        <div className="mt-2 flex flex-wrap gap-1">
          {rows.map(r => (
            <span key={r.value} className="rounded-full bg-muted px-2 py-0.5 text-xs">
              {r.value} · {r.count}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

export function ResearchFailureAnalysisPanel({ experimentId }: { experimentId: string }) {
  const [runId, setRunId] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(25)
  const [refreshing, setRefreshing] = useState(false)

  const runsQuery = useQuery({
    queryKey: ['research-runs', experimentId],
    queryFn: () => researchApi.listRuns(experimentId),
    select: data => data.data.data,
    enabled: experimentId.length > 0,
  })

  const reportQuery = useQuery({
    queryKey: ['research-failure-analysis', experimentId, runId],
    queryFn: () => researchApi.getFailureAnalysis(experimentId, runId || undefined),
    select: data => data.data.data,
    enabled: experimentId.length > 0,
    staleTime: 0,
  })

  const samplesQuery = useQuery({
    queryKey: ['research-failure-samples', experimentId, runId, page, pageSize],
    queryFn: () =>
      researchApi.getFailureSamples(experimentId, {
        runId: runId || undefined,
        page,
        pageSize,
      }),
    select: data => data.data.data,
    enabled: experimentId.length > 0 && (reportQuery.data?.failureCaseCount ?? 0) > 0,
    staleTime: 0,
  })

  const runs: ResearchRun[] = useMemo(() => runsQuery.data ?? [], [runsQuery.data])
  const report = reportQuery.data
  const samplesPage: FailureSamplesPage | undefined = samplesQuery.data

  const available = report !== undefined && report.evaluableSampleCount > 0
  const canListSamples = available && report.failureCaseCount > 0

  const refresh = async () => {
    setRefreshing(true)
    await Promise.all([reportQuery.refetch(), samplesQuery.refetch()])
    setRefreshing(false)
  }

  const goToPage = (next: number) => {
    const totalPages = samplesPage?.totalPages ?? 1
    setPage(Math.min(Math.max(next, 0), Math.max(totalPages - 1, 0)))
  }

  const changePageSize = (size: number) => {
    setPageSize(size)
    setPage(0)
  }

  return (
    <div className="space-y-4" data-testid="research-failure-analysis-panel">
      <Card className="p-4">
        <PanelHeader icon={<ShieldAlert size={16} className="text-muted-foreground" />} title="Failure-case analysis" />
        <p className="mt-2 text-xs text-muted-foreground">
          Deterministic, read-only case analysis over the persisted controlled dataset. Failure cases are
          real evaluable samples where the baseline or fusion prediction disagreed with the resolved
          human-review ground truth. Nothing is generated, simulated or inferred, and no student identity,
          raw media or demographics are ever exported.
        </p>

        <div className="mt-3 flex flex-wrap items-center gap-3">
          <select
            value={runId}
            onChange={e => {
              setRunId(e.target.value)
              setPage(0)
            }}
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
            <span className="rounded-full bg-muted px-2 py-0.5">{report.failureAnalysisVersion}</span>
            <span className="rounded-full bg-muted px-2 py-0.5">
              Generated {new Date(report.generatedAt).toLocaleString()}
            </span>
          </div>
        )}
      </Card>

      {!report ? (
        <Card className="flex flex-col items-center gap-2 p-8 text-center">
          <ShieldAlert size={24} className="text-muted-foreground" />
          <p className="text-sm font-medium">Failure analysis unavailable.</p>
          <p className="max-w-md text-xs text-muted-foreground">
            Failure-case analysis is available only for experiments with at least one captured sample. No
            results are fabricated for empty scopes.
          </p>
        </Card>
      ) : !available ? (
        <Card className="flex flex-col items-center gap-2 p-8 text-center">
          <Bug size={24} className="text-muted-foreground" />
          <p className="text-sm font-medium">No evaluable samples in this scope.</p>
          <p className="max-w-md text-xs text-muted-foreground">
            Capture and review samples to populate the analysis. Until then every count, band and pattern is
            reported as unavailable (never zero-by-inference).
          </p>
        </Card>
      ) : (
        <>
          {report.evaluableSampleCount > 0 && report.evaluableSampleCount < 5 && (
            <Card className="flex items-start gap-2 p-4">
              <Info size={15} className="mt-0.5 text-amber-600" />
              <div className="text-xs text-muted-foreground">
                <p className="font-medium text-foreground">Small-sample scope.</p>
                <p className="mt-1">
                  This scope has fewer than 5 evaluable samples. Counts and rates below are strictly
                  descriptive of these observations only and never treated as characteristic of the system.
                </p>
              </div>
            </Card>
          )}
          <div className="grid gap-4 lg:grid-cols-3">
            <Card className="p-4 lg:col-span-3">
              <PanelHeader icon={<Bug size={16} className="text-muted-foreground" />} title="Error overview" />
              <p className="mt-2 text-xs text-muted-foreground">
                Failure cases are evaluable samples where either detector produced a prediction that differs
                from the resolved human-review ground truth. Categories are always listed in a fixed order.
              </p>
              <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
                <Metric label="Evaluable samples" value={String(report.evaluableSampleCount)} />
                <Metric label="Failure cases" value={String(report.failureCaseCount)} />
                <Metric label="Baseline ↔ fusion disagreements" value={String(report.disagreementCount)} />
                <Metric label="Changed predictions" value={String(report.changedPredictionCount)} />
              </div>
              <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {report.categories.map(c => (
                  <div key={c.value} className="flex items-center justify-between rounded-xl border px-3 py-2 text-sm">
                    <span className="font-medium">{CATEGORY_LABELS[c.value] ?? c.value}</span>
                    <span className="text-muted-foreground">{c.count}</span>
                  </div>
                ))}
              </div>
              {report.patterns.length > 0 && (
                <div className="mt-3 space-y-1">
                  {report.patterns.map((p, i) => (
                    <p key={`${p.text}-${i}`} className="text-xs text-muted-foreground">• {p.text}</p>
                  ))}
                </div>
              )}
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <PanelHeader icon={<Shuffle size={16} className="text-muted-foreground" />} title="Baseline ↔ fusion disagreements" />
              <p className="mt-2 text-xs text-muted-foreground">
                Evaluable samples where the binary baseline and the confidence-aware fusion predicted
                differently. Predictions are compared to the resolved ground truth exclusively.
              </p>
              <div className="mt-3 grid gap-2 sm:grid-cols-2">
                {report.disagreementCategories.map(c => (
                  <div key={c.value} className="flex items-center justify-between rounded-xl border px-3 py-2 text-sm">
                    <span className="font-medium">{CATEGORY_LABELS[c.value] ?? c.value}</span>
                    <span className="text-muted-foreground">{c.count}</span>
                  </div>
                ))}
              </div>
            </Card>
            <Card className="p-4">
              <PanelHeader icon={<Fingerprint size={16} className="text-muted-foreground" />} title="Prediction transitions" />
              <p className="mt-2 text-xs text-muted-foreground">
                Where fusion changed the baseline prediction, per observed transition direction.
              </p>
              <div className="mt-3 grid gap-2 sm:grid-cols-2">
                {report.transitionCategories.map(c => (
                  <div key={c.value} className="flex items-center justify-between rounded-xl border px-3 py-2 text-sm">
                    <span className="font-medium">{TRANSITION_LABELS[c.value] ?? c.value}</span>
                    <span className="text-muted-foreground">{c.count}</span>
                  </div>
                ))}
              </div>
            </Card>
          </div>

          <Card className="p-4">
            <PanelHeader icon={<Shuffle size={16} className="text-muted-foreground" />} title="Error transitions" />
            <p className="mt-2 text-xs text-muted-foreground">
              A transition is an evaluable sample whose prediction differed between baseline and fusion,
              described as correct→wrong or wrong→correct relative to the resolved ground truth.
            </p>
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Metric label="Changed predictions" value={String(report.transitions.changedPredictionCount)} />
              <Metric label="Correct → wrong" value={String(report.transitions.baselineCorrectToFusionWrong)} />
              <Metric label="Wrong → correct" value={String(report.transitions.baselineWrongToFusionCorrect)} />
              <Metric label="Share correct→wrong" value={pct(report.transitions.baselineCorrectToFusionWrongFraction)} />
            </div>
            <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <DistributionTable title="By scenario" rows={report.transitions.byScenario} />
              <DistributionTable title="By controlled condition" rows={report.transitions.byCondition} />
              <DistributionTable title="By signal source" rows={report.transitions.bySignalSource} />
              <DistributionTable title="By AI confidence band" rows={report.transitions.byConfidenceBand} />
            </div>
          </Card>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <PanelHeader icon={<BarChart3 size={16} className="text-muted-foreground" />} title="Failure cases by scenario" />
              <p className="mt-2 text-xs text-muted-foreground">
                Per-scenario false positives and false negatives. Groups under 5 samples are flagged and
                shown as descriptive only.
              </p>
              <GroupTable groups={report.scenarioFailures} kind="scenario" />
            </Card>
            <Card className="p-4">
              <PanelHeader icon={<Layers size={16} className="text-muted-foreground" />} title="Failure cases by controlled condition" />
              <p className="mt-2 text-xs text-muted-foreground">
                Per controlled environmental condition (lighting, camera quality, network, camera angle) —
                never demographic. {report.missingConditionCount > 0 && `${report.missingConditionCount} evaluable sample(s) lacked a value for some condition.`}
              </p>
              <GroupTable groups={report.environmentalFailures} kind="environment" />
            </Card>
          </div>

          <Card className="p-4">
            <PanelHeader icon={<BarChart3 size={16} className="text-muted-foreground" />} title="Signal profile of failure cases" />
            <p className="mt-2 text-xs text-muted-foreground">
              Signal types and sources recorded on failure cases, with descriptive confidence and duration
              stats where actually measured. {report.missingConfidenceSignalCount > 0 && `${report.missingConfidenceSignalCount} AI signal(s) had no confidence score.`}
            </p>
            {report.signalProfile.length === 0 ? (
              <p className="mt-3 text-xs text-muted-foreground">No signals recorded on failure cases in this scope.</p>
            ) : (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">Category</th>
                      <th className="py-2 pr-3">Source</th>
                      <th className="py-2 pr-3">Signals</th>
                      <th className="py-2 pr-3">Samples</th>
                      <th className="py-2 pr-3">Confidence mean</th>
                      <th className="py-2 pr-3">Confidence median</th>
                      <th className="py-2 pr-3">Duration mean</th>
                      <th className="py-2 pr-3">Duration median</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.signalProfile.map(row => (
                      <tr key={`${row.category}-${row.source}`} className="border-b last:border-0">
                        <td className="py-2 pr-3 font-medium">{row.category}</td>
                        <td className="py-2 pr-3">{row.source}</td>
                        <td className="py-2 pr-3">{row.signalCount}</td>
                        <td className="py-2 pr-3">{row.sampleCount}</td>
                        <td className="py-2 pr-3">{fmt(row.confidence.mean)}</td>
                        <td className="py-2 pr-3">{fmt(row.confidence.median)}</td>
                        <td className="py-2 pr-3">{row.duration.mean === null ? '—' : `${row.duration.mean.toFixed(0)} ms`}</td>
                        <td className="py-2 pr-3">{row.duration.median === null ? '—' : `${row.duration.median.toFixed(0)} ms`}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<Layers size={16} className="text-muted-foreground" />} title="Failure cases by AI confidence band" />
            <p className="mt-2 text-xs text-muted-foreground">
              Failure cases grouped by the highest AI confidence recorded on the sample. The same sample
              never appears in more than one band, so bands are directly comparable.
            </p>
            {report.confidenceBands.length === 0 ? (
              <p className="mt-3 text-xs text-muted-foreground">No AI-confidence-bearing failure cases in this scope.</p>
            ) : (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">Band</th>
                      <th className="py-2 pr-3">Samples</th>
                      <th className="py-2 pr-3">Signals</th>
                      <th className="py-2 pr-3">Precision</th>
                      <th className="py-2 pr-3">Recall</th>
                      <th className="py-2 pr-3">FP rate</th>
                      <th className="py-2 pr-3">FN rate</th>
                      <th className="py-2 pr-3">FDR</th>
                      <th className="py-2 pr-3">Evaluator</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.confidenceBands.map(b => (
                      <tr key={b.band} className="border-b last:border-0">
                        <td className="py-2 pr-3 font-medium">{b.band}</td>
                        <td className="py-2 pr-3">{b.distinctSampleCount}</td>
                        <td className="py-2 pr-3">{b.signalCount}</td>
                        <td className="py-2 pr-3">{pct(b.precision)}</td>
                        <td className="py-2 pr-3">{pct(b.recall)}</td>
                        <td className="py-2 pr-3">{pct(b.falsePositiveRate)}</td>
                        <td className="py-2 pr-3">{pct(b.falseNegativeRate)}</td>
                        <td className="py-2 pr-3">{pct(b.falseDiscoveryRate)}</td>
                        <td className="py-2 pr-3">{b.evaluator}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<AlertTriangle size={16} className="text-muted-foreground" />} title="Failure samples" />
            <p className="mt-2 text-xs text-muted-foreground">
              Individual failure cases, ordered by research sample id so paging is stable. Signal metadata is
              aggregated trivia — never raw media, faces, audio, identity or demographics.
            </p>
            {!canListSamples ? (
              <p className="mt-3 text-xs text-muted-foreground">No failure cases to list in this scope.</p>
            ) : (
              <>
                <div className="mt-3 overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b text-left text-xs text-muted-foreground">
                        <th className="py-2 pr-3">Sample</th>
                        <th className="py-2 pr-3">Scenario</th>
                        <th className="py-2 pr-3">Ground truth</th>
                        <th className="py-2 pr-3">Baseline</th>
                        <th className="py-2 pr-3">Fusion</th>
                        <th className="py-2 pr-3">Transition</th>
                        <th className="py-2 pr-3">Conditions</th>
                        <th className="py-2 pr-3">Signals</th>
                        <th className="py-2 pr-3">Confidence</th>
                        <th className="py-2 pr-3">Duration</th>
                      </tr>
                    </thead>
                    <tbody>
                      {samplesQuery.isLoading ? (
                        <tr><td colSpan={10} className="py-3 text-xs text-muted-foreground">Loading failure cases…</td></tr>
                      ) : (samplesPage?.samples ?? []).map((s: FailureCase) => (
                        <tr key={s.sampleId} className="border-b last:border-0">
                          <td className="py-2 pr-3 text-xs text-muted-foreground">{s.sampleId}</td>
                          <td className="py-2 pr-3">{s.scenario ?? '—'}</td>
                          <td className="py-2 pr-3">{s.groundTruth}</td>
                          <td className="py-2 pr-3">{s.baselinePrediction ? 'positive' : 'negative'}</td>
                          <td className="py-2 pr-3">{s.fusionPrediction ? 'positive' : 'negative'}</td>
                          <td className="py-2 pr-3">{s.classification}</td>
                          <td className="py-2 pr-3 text-xs text-muted-foreground">{s.condition}</td>
                          <td className="py-2 pr-3 text-xs text-muted-foreground">
                            {s.signalTypes.length === 0 ? '—' : `${s.signalTypes.join(', ')} via ${s.signalSources.join(', ')}`}
                          </td>
                          <td className="py-2 pr-3 text-xs text-muted-foreground">{rangeText(s.confidenceRange)}</td>
                          <td className="py-2 pr-3 text-xs text-muted-foreground">{rangeText(s.durationRange)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {samplesPage && (
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                    <div className="flex items-center gap-2 text-xs text-muted-foreground">
                      <span>
                        Page {samplesPage.totalPages === 0 ? 0 : samplesPage.page + 1} of {samplesPage.totalPages}
                        {' '}· {samplesPage.totalCount} failure case(s)
                      </span>
                      <select
                        value={pageSize}
                        onChange={e => changePageSize(Number(e.target.value))}
                        className="field w-auto py-1 text-xs"
                        aria-label="Page size"
                      >
                        {PAGE_SIZES.map(size => (
                          <option key={size} value={size}>{size} per page</option>
                        ))}
                      </select>
                    </div>
                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => goToPage(page - 1)}
                        disabled={page <= 0 || samplesQuery.isLoading}
                        className="flex items-center gap-1 rounded-xl border px-3 py-1.5 text-sm disabled:opacity-50"
                      >
                        <ChevronLeft size={14} /> Previous
                      </button>
                      <button
                        onClick={() => goToPage(page + 1)}
                        disabled={(samplesPage.totalPages <= 1) || page >= samplesPage.totalPages - 1 || samplesQuery.isLoading}
                        className="flex items-center gap-1 rounded-xl border px-3 py-1.5 text-sm disabled:opacity-50"
                      >
                        Next <ChevronRight size={14} />
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}
          </Card>

          <Card className="p-4">
            <PanelHeader icon={<Info size={16} className="text-muted-foreground" />} title="Limitations" />
            <ul className="mt-3 list-disc pl-5 text-xs text-muted-foreground">
              <li>Observed associations in the controlled dataset must not be interpreted as causal relationships.</li>
              <li>
                Every count, band and pattern is computed only from evaluable samples with a resolved
                human-review ground truth; nothing is estimated, simulated or completed by inference.
              </li>
              <li>Small groups (under 5 samples) are flagged and always remain purely descriptive.</li>
              <li>
                Fusion-only-positive cases are impossible for the current engine and are therefore never
                displayed as a category.
              </li>
              <li>No demographic, biometric or raw-media data is captured — no such stratification exists.</li>
            </ul>
          </Card>
        </>
      )}
    </div>
  )
}
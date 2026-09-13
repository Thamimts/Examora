'use client'
import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { AlertCircle, BarChart3, Monitor, ShieldAlert, Users, X } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { analyticsApi } from '@/services/analyticsApi'
import { proctorApi } from '@/services/proctorApi'
import { examApi } from '@/services/examApi'
import { questionApi } from '@/services/questionApi'
import { Card, Header } from '@/components/shared'
import type { ExamAnalyticsDetail, QuestionAnalytics, StudentPerformanceRow } from '@/types/analytics'
import type { OptionLabel } from '@/types/analytics'
import {
  AttentionChips,
  EmptyState,
  MetricTile,
  OptionBars,
  RiskBadge,
  StatusPill,
  attentionFlags,
  formatDateTime,
  formatDuration,
  formatPercent,
} from './intelligence-common'

const BUCKET_COLORS = ['hsl(var(--destructive))', 'hsl(var(--destructive))', 'hsl(var(--accent))', 'hsl(var(--primary))', 'hsl(var(--primary))']

function difficultyLabel(level: number): string {
  return level <= 1 ? 'Easy' : level >= 3 ? 'Hard' : 'Medium'
}

function BarDrawer({ questionId, examId }: { questionId: string; examId: string }) {
  const detailQuery = useQuery({
    queryKey: ['analytics-detail', examId],
    queryFn: async () => (await analyticsApi.examDetail(examId)).data.data,
    staleTime: 60_000,
    retry: 1,
  })
  const optionsQuery = useQuery({
    queryKey: ['analytics-options', examId],
    queryFn: async () => (await analyticsApi.examOptionLabels(examId)).data.data,
    staleTime: 60_000,
    retry: 1,
  })
  const questionsQuery = useQuery({
    queryKey: ['exam-questions', examId],
    queryFn: async () => (await questionApi.list(examId)).data.data,
    staleTime: 60_000,
    retry: 1,
  })

  const question: QuestionAnalytics | undefined = detailQuery.data?.questionAnalytics.find(q => q.questionId === questionId)
  const questionText = questionsQuery.data?.find(q => q.id === questionId)?.text ?? ''
  const labels: OptionLabel[] = optionsQuery.data?.filter(option => option.questionId === questionId) ?? []
  const answered = question ? question.correct + question.incorrect : 0

  if (detailQuery.isPending || optionsQuery.isPending || questionsQuery.isPending) {
    return <div className="space-y-3 py-6" aria-busy="true">{[1, 2, 3].map(item => <div key={item} className="h-12 animate-pulse rounded-xl bg-muted" />)}</div>
  }
  if (!question) {
    return <EmptyState title="Question data unavailable." />
  }

  return (
    <div className="space-y-6">
      <div>
        <p className="text-xs uppercase tracking-widest text-muted-foreground">
          Question {question.number} · {difficultyLabel(question.difficulty)}
        </p>
        <h3 className="mt-2 text-lg font-semibold leading-snug">{questionText || 'Question text unavailable.'}</h3>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Correct</p>
          <p className="mt-1 text-xl font-semibold text-emerald-600">{question.correct}</p>
        </div>
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Incorrect</p>
          <p className="mt-1 text-xl font-semibold text-red-600">{question.incorrect}</p>
        </div>
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Accuracy</p>
          <p className="mt-1 text-xl font-semibold">{formatPercent(question.accuracy)}</p>
        </div>
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Attempt rate</p>
          <p className="mt-1 text-xl font-semibold">{formatPercent(question.attemptRate)}</p>
        </div>
      </div>

      <div>
        <p className="text-sm font-medium">Answer distribution over submitted attempts</p>
        <OptionBars distribution={question.optionDistribution} labels={labels} answered={answered} />
      </div>

      {question.flags.length ? (
        <div>
          <p className="text-sm font-medium">Flags</p>
          <div className="mt-2">
            <AttentionChips flags={question.flags} />
          </div>
        </div>
      ) : null}

      <div className="grid grid-cols-2 gap-3 text-xs text-muted-foreground sm:grid-cols-4">
        <p>Eligible: {question.eligibleAttempts}</p>
        <p>Graded: {question.gradedAttempts}</p>
        <p>Ungraded: {question.ungradedAnswers}</p>
        <p>Unanswered: {question.unanswered}</p>
      </div>
    </div>
  )
}

function StudentDrawer({ student, examId }: { student: StudentPerformanceRow; examId: string }) {
  const drilldownQuery = useQuery({
    queryKey: ['analytics-student-drilldown', student.studentId],
    queryFn: async () => (await analyticsApi.studentDrilldown(student.studentId)).data.data,
    staleTime: 30_000,
    retry: 1,
  })
  const data = drilldownQuery.data
  const completion =
    student.hasResult
      ? { label: 'Submitted', className: 'text-blue-600 bg-blue-500/10' }
      : student.activeNow
        ? { label: 'In progress', className: 'text-emerald-600 bg-emerald-500/10' }
        : student.attemptStatus
          ? { label: 'Timed out', className: 'text-amber-600 bg-amber-500/10' }
          : { label: 'No attempt', className: 'bg-muted text-muted-foreground' }

  if (drilldownQuery.isPending) {
    return <div className="space-y-3 py-6" aria-busy="true">{[1, 2, 3].map(item => <div key={item} className="h-12 animate-pulse rounded-xl bg-muted" />)}</div>
  }
  if (!data) {
    return <EmptyState title="Student details are unavailable." />
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h3 className="text-lg font-semibold">{student.studentName}</h3>
          <p className="mt-0.5 text-xs text-muted-foreground">{data.studentId}</p>
        </div>
        <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${completion.className}`}>{completion.label}</span>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Score</p>
          <p className="mt-1 text-xl font-semibold">
            {student.score != null && student.total != null ? `${student.score}/${student.total}` : '—'}
          </p>
          <p className="mt-0.5 text-xs text-muted-foreground">{formatPercent(student.percentage)}</p>
        </div>
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Duration</p>
          <p className="mt-1 text-xl font-semibold">{formatDuration(student.durationSeconds)}</p>
        </div>
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Practice accuracy</p>
          <p className="mt-1 text-xl font-semibold">{formatPercent(student.practiceAccuracy)}</p>
        </div>
        <div className="rounded-xl border border-border p-3">
          <p className="text-xs text-muted-foreground">Practice answers</p>
          <p className="mt-1 text-xl font-semibold">
            {student.practiceQuestions ? `${student.practiceCorrect}/${student.practiceQuestions}` : '—'}
          </p>
        </div>
      </div>

      {student.submittedAt ? <p className="text-xs text-muted-foreground">Submitted {formatDateTime(student.submittedAt)}</p> : null}

      <div>
        <p className="text-sm font-medium">Performance history</p>
        {data.examHistory.length ? (
          <div className="mt-3 divide-y divide-border">
            {data.examHistory.map(exam => (
              <div key={exam.examId} className="flex items-center justify-between gap-3 py-3 text-sm first:pt-0">
                <div>
                  <p className="font-medium">{exam.examTitle}</p>
                  <p className="mt-0.5 text-xs text-muted-foreground">{exam.subject} · {exam.date}</p>
                </div>
                <p className="font-semibold">{formatPercent(exam.percentage)}</p>
              </div>
            ))}
          </div>
        ) : (
          <p className="mt-3 text-sm text-muted-foreground">No prior exam history.</p>
        )}
      </div>
    </div>
  )
}

export function ExamIntelligence() {
  const navigate = useNavigate()
  const { examId = '' } = useParams()
  const [openQuestion, setOpenQuestion] = useState<QuestionAnalytics | null>(null)
  const [openStudent, setOpenStudent] = useState<StudentPerformanceRow | null>(null)

  const detailQuery = useQuery({
    queryKey: ['analytics-detail', examId],
    queryFn: async () => (await analyticsApi.examDetail(examId)).data.data,
    retry: 1,
  })
  const examQuery = useQuery({
    queryKey: ['exam', examId],
    queryFn: async () => (await examApi.get(examId)).data.data,
    retry: 1,
  })
  const studentsQuery = useQuery({
    queryKey: ['analytics-students', examId],
    queryFn: async () => (await analyticsApi.examStudents(examId)).data.data,
    retry: 1,
  })
  const proctorQuery = useQuery({
    queryKey: ['proctor-summary', examId],
    queryFn: async () => (await proctorApi.summary(examId)).data.data,
    retry: 1,
  })

  const detail = detailQuery.data
  const students = studentsQuery.data ?? []
  const proctor = proctorQuery.data

  const distributionData = useMemo(
    () => (detail?.scoreDistribution ?? []).map(bucket => ({ range: bucket.range, count: bucket.count })),
    [detail],
  )

  const flaggedQuestions = detail?.questionAnalytics.filter(question => attentionFlags(question).length > 0) ?? []

  const studentsWithFlags = useMemo(() => {
    const max = students.length ? Math.max(...students.filter(s => s.hasResult).map(s => s.percentage ?? 0), -1) : -1
    const flagged = students.filter(s => s.hasResult && s.percentage != null && max >= 0 && s.percentage <= Math.max(max * 0.5, 40))
    return flagged.sort((a, b) => (a.percentage ?? 0) - (b.percentage ?? 0))
  }, [students])

  if (detailQuery.isError) {
    return (
      <>
        <Header title="Exam intelligence" description="Performance analytics for this assessment." />
        <Card className="border-destructive/30">
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <AlertCircle size={20} className="text-destructive" />
              <p className="text-sm text-destructive">Unable to load this exam's analytics.</p>
            </div>
            <button type="button" className="rounded-lg border border-border px-3 py-2 text-sm hover:bg-muted" onClick={() => detailQuery.refetch()}>
              Retry
            </button>
          </div>
        </Card>
      </>
    )
  }

  const exam = examQuery.data
  const examTitle = exam?.title ?? detail?.title ?? 'Exam intelligence'
  const completion = proctor ? (proctor.activeAttempts > proctor.eventCount ? `${proctor.activeAttempts} active now` : `${proctor.eventCount} proctor events`) : null

  return (
    <>
      <Header
        title={examTitle}
        description="Per-question and per-student performance for this assessment, computed from real submissions."
      />

      {detailQuery.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-busy="true">
          {[1, 2, 3, 4].map(item => <Card key={item}><div className="h-14 animate-pulse rounded-xl bg-muted" /></Card>)}
        </div>
      ) : (
        <>
          <div className="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <MetricTile label="Status" value={<StatusPill status={detail?.status} />} sub={exam?.subject} />
            <MetricTile label="Students" value={detail?.participants ?? 0} sub={`${detail?.submissions ?? 0} submitted`} />
            <MetricTile label="Average score" value={formatPercent(detail?.averageScore ?? null)} sub={`median ${formatPercent(detail?.medianScore ?? null)}`} />
            <MetricTile label="Completion" value={formatPercent(detail?.completionRate ?? null)} sub={`${detail?.attemptsSubmitted ?? 0} of ${detail?.attemptsStarted ?? 0} attempts started`} />
          </div>

          <div className="mb-6 grid gap-6 lg:grid-cols-[1.4fr_1fr]">
            <Card>
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <BarChart3 className="text-primary" size={18} />
                  <h2 className="font-semibold">Score distribution</h2>
                </div>
                {detail ? (
                  <p className="text-xs text-muted-foreground">
                    {detail.highestScore != null ? `Top ${detail.highestScore}% · ${detail.lowestScore}% low` : 'No submissions yet'}
                  </p>
                ) : null}
              </div>
              {distributionData.some(item => item.count > 0) ? (
                <div className="mt-5 h-64" aria-label="Score distribution chart">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={distributionData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                      <XAxis dataKey="range" tick={{ fontSize: 11 }} />
                      <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                      <Tooltip />
                      <Bar dataKey="count" radius={[6, 6, 0, 0]}>
                        {distributionData.map((entry, index) => (
                          <Cell key={entry.range} fill={BUCKET_COLORS[index] ?? 'hsl(var(--primary))'} />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <EmptyState title="No submissions yet" hint="Score bands will appear once students submit." />
              )}
            </Card>

            <Card>
              <div className="flex items-center gap-2">
                <Users className="text-primary" size={18} />
                <h2 className="font-semibold">Student performance</h2>
              </div>
              {students.length ? (
                <div className="mt-4 divide-y divide-border">
                  {students.slice(0, 6).map(student => (
                    <button
                      key={student.studentId}
                      type="button"
                      onClick={() => setOpenStudent(student)}
                      className="flex w-full items-center justify-between gap-3 py-3 text-left transition hover:bg-muted/60 focus-visible:outline-none"
                    >
                      <span className="min-w-0">
                        <b className="block truncate text-sm">{student.studentName}</b>
                        <span className="mt-0.5 block text-xs text-muted-foreground">
                          {student.hasResult ? 'Submitted' : student.activeNow ? 'In progress' : 'No submission'}
                        </span>
                      </span>
                      <span className="font-semibold text-sm">{formatPercent(student.percentage)}</span>
                    </button>
                  ))}
                </div>
              ) : (
                <EmptyState title="No students yet" hint="Students appear here once they attempt the exam." />
              )}
            </Card>
          </div>

          {flaggedQuestions.length ? (
            <Card className="mb-6 border-red-500/30">
              <div className="flex items-center gap-2">
                <ShieldAlert className="text-red-600" size={18} />
                <h2 className="font-semibold">Attention needed</h2>
                <span className="ml-auto rounded-full bg-red-500/10 px-2.5 py-0.5 text-xs font-medium text-red-600">
                  {flaggedQuestions.length} flagged {flaggedQuestions.length === 1 ? 'question' : 'questions'}
                </span>
              </div>
              <div className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                {flaggedQuestions.slice(0, 6).map(question => (
                  <button
                    key={question.questionId}
                    type="button"
                    onClick={() => setOpenQuestion(question)}
                    className="rounded-xl border border-border p-4 text-left transition hover:bg-muted/50 focus-visible:outline-none"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <p className="text-sm font-medium">Q{question.number}</p>
                      <p className="text-xs text-muted-foreground">{formatPercent(question.accuracy)} accuracy</p>
                    </div>
                    <div className="mt-2">
                      <AttentionChips flags={attentionFlags(question)} />
                    </div>
                  </button>
                ))}
              </div>
            </Card>
          ) : null}

          {studentsWithFlags.length ? (
            <Card className="mb-6 border-amber-500/30">
              <div className="flex items-center gap-2">
                <AlertCircle className="text-amber-600" size={18} />
                <h2 className="font-semibold">Low performers</h2>
                <span className="ml-auto rounded-full bg-amber-500/10 px-2.5 py-0.5 text-xs font-medium text-amber-600">
                  {studentsWithFlags.length} flagged student{studentsWithFlags.length === 1 ? '' : 's'}
                </span>
              </div>
              <div className="mt-4 divide-y divide-border">
                {studentsWithFlags.map(student => (
                  <button
                    key={student.studentId}
                    type="button"
                    onClick={() => setOpenStudent(student)}
                    className="flex w-full items-center justify-between gap-3 py-3 text-left transition hover:bg-muted/60 focus-visible:outline-none"
                  >
                    <span>
                      <b className="block text-sm">{student.studentName}</b>
                      <span className="mt-0.5 block text-xs text-muted-foreground">{formatPercent(student.practiceAccuracy)} practice accuracy</span>
                    </span>
                    <span className="text-sm font-semibold text-red-600">{formatPercent(student.percentage)}</span>
                  </button>
                ))}
              </div>
            </Card>
          ) : null}

          <Card>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="font-semibold">Question intelligence</h2>
              {detail ? (
                <p className="text-xs text-muted-foreground">
                  {detail.questionAnalytics.length} questions · updated from submitted attempts
                </p>
              ) : null}
            </div>
            {detail?.questionAnalytics.length ? (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full min-w-[720px] text-left text-sm">
                  <thead>
                    <tr className="border-b border-border text-xs uppercase tracking-wider text-muted-foreground">
                      <th className="pb-3 pr-3 font-medium">Q</th>
                      <th className="pb-3 pr-3 font-medium">Difficulty</th>
                      <th className="pb-3 pr-3 text-right font-medium">Attempted</th>
                      <th className="pb-3 pr-3 text-right font-medium">Correct</th>
                      <th className="pb-3 pr-3 text-right font-medium">Incorrect</th>
                      <th className="pb-3 pr-3 text-right font-medium">Unanswered</th>
                      <th className="pb-3 pr-3 text-right font-medium">Accuracy</th>
                      <th className="pb-3 pr-3 text-right font-medium">Attempt rate</th>
                      <th className="pb-3 font-medium">Flags</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {detail.questionAnalytics.map(question => (
                      <tr key={question.questionId} className="transition hover:bg-muted/40">
                        <td className="py-3 pr-3">
                          <button
                            type="button"
                            onClick={() => setOpenQuestion(question)}
                            className="font-medium text-primary hover:underline"
                          >
                            Q{question.number}
                          </button>
                        </td>
                        <td className="py-3 pr-3">{difficultyLabel(question.difficulty)}</td>
                        <td className="py-3 pr-3 text-right">{question.gradedAttempts}</td>
                        <td className="py-3 pr-3 text-right text-emerald-600">{question.correct}</td>
                        <td className="py-3 pr-3 text-right text-red-600">{question.incorrect}</td>
                        <td className="py-3 pr-3 text-right">{question.unanswered}</td>
                        <td className="py-3 pr-3 text-right font-medium">{formatPercent(question.accuracy)}</td>
                        <td className="py-3 pr-3 text-right">{formatPercent(question.attemptRate)}</td>
                        <td className="py-3">
                          <AttentionChips flags={attentionFlags(question)} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState title="No question analytics yet" hint="Add and publish questions to see grading intelligence." />
            )}
          </Card>

          <Card className="mt-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="font-semibold">Live monitoring</h2>
                <p className="mt-1 text-sm text-muted-foreground">Session health, risk signals, and real-time activity.</p>
              </div>
              <button
                type="button"
                onClick={() => navigate(`/teacher/monitor/${examId}`)}
                className="inline-flex items-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition hover:bg-primary/90"
              >
                <Monitor size={16} />
                Open monitor
              </button>
            </div>
            {proctor ? (
              proctor.hasAttempts ? (
                <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
                  <div className="rounded-xl border border-border p-3">
                    <p className="text-xs text-muted-foreground">Attempts</p>
                    <p className="mt-1 text-xl font-semibold">{proctor.totalAttempts}</p>
                  </div>
                  <div className="rounded-xl border border-border p-3">
                    <p className="text-xs text-muted-foreground">Active now</p>
                    <p className="mt-1 text-xl font-semibold">{proctor.activeAttempts}</p>
                  </div>
                  <div className="rounded-xl border border-border p-3">
                    <p className="text-xs text-muted-foreground">Proctor events</p>
                    <p className="mt-1 text-xl font-semibold">{proctor.eventCount}</p>
                  </div>
                  <div className="rounded-xl border border-border p-3">
                    <p className="text-xs text-muted-foreground">Risk</p>
                    <div className="mt-1.5">
                      <RiskBadge level={proctor.riskLevel} score={proctor.riskScore} />
                    </div>
                  </div>
                  <div className="rounded-xl border border-border p-3">
                    <p className="text-xs text-muted-foreground">Overview</p>
                    <p className="mt-1 text-sm font-medium">{completion}</p>
                  </div>
                </div>
              ) : (
                <p className="mt-4 text-sm text-muted-foreground">
                  No attempts to monitor yet. The monitor opens as soon as students start.
                </p>
              )
            ) : (
              <p className="mt-4 text-sm text-muted-foreground">Proctoring summary is unavailable.</p>
            )}
          </Card>
        </>
      )}

      {openQuestion ? (
        <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label="Question analytics">
          <button type="button" aria-label="Close" className="absolute inset-0 bg-black/40" onClick={() => setOpenQuestion(null)} />
          <div className="relative h-full w-full max-w-lg overflow-y-auto border-l border-border bg-background p-6">
            <div className="mb-6 flex items-center justify-between">
              <p className="text-sm font-medium uppercase tracking-widest text-muted-foreground">Question analytics</p>
              <button type="button" onClick={() => setOpenQuestion(null)} className="rounded-lg p-2 hover:bg-muted" aria-label="Close">
                <X size={18} />
              </button>
            </div>
            <BarDrawer questionId={openQuestion.questionId} examId={examId} />
          </div>
        </div>
      ) : null}

      {openStudent ? (
        <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label="Student details">
          <button type="button" aria-label="Close" className="absolute inset-0 bg-black/40" onClick={() => setOpenStudent(null)} />
          <div className="relative h-full w-full max-w-lg overflow-y-auto border-l border-border bg-background p-6">
            <div className="mb-6 flex items-center justify-between">
              <p className="text-sm font-medium uppercase tracking-widest text-muted-foreground">Student performance</p>
              <button type="button" onClick={() => setOpenStudent(null)} className="rounded-lg p-2 hover:bg-muted" aria-label="Close">
                <X size={18} />
              </button>
            </div>
            <StudentDrawer student={openStudent} examId={examId} />
          </div>
        </div>
      ) : null}
    </>
  )
}
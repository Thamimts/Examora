'use client'
import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Check, CircleX, GraduationCap, Lightbulb, Loader2, Sparkles, WandSparkles } from 'lucide-react'
import { aiTutorApi } from '@/services/aiTutorApi'
import { useAuthStore } from '@/store/authStore'
import type { AIPracticeReviewQuestion, AiTutorAction, AiTutorAnswerResult, AiTutorExplanation, AiTutorQuestion } from '@/types/ai'

const ACTION_LABELS: Record<AiTutorAction, string> = {
  EXPLAIN_WRONG: 'Why I got it wrong',
  EXPLAIN_CONCEPT: 'Explain the concept',
  EXPLAIN_SIMPLE: 'Explain simply',
  GIVE_EXAMPLE: 'Give an example',
  SIMILAR_QUESTION: 'Similar question',
  HARDER_QUESTION: 'Harder question',
}

const OPTION_LETTERS = ['A', 'B', 'C', 'D']

const RATE_LIMITED_MESSAGE = 'The AI tutor has reached its temporary usage limit. Try again in a few minutes.'
const EXPIRED_MESSAGE = 'Your session has expired. Please sign in again.'
const FORBIDDEN_MESSAGE = "You don't have access to this question or session."
const GONE_MESSAGE = 'This question or session is no longer available. Refresh the review to reload the latest data.'
const ALREADY_ANSWERED_MESSAGE = 'This question has already been answered.'

export function tutorActionsFor(question: AIPracticeReviewQuestion): AiTutorAction[] {
  if (question.answered && !question.correct) {
    return ['EXPLAIN_WRONG', 'EXPLAIN_CONCEPT', 'EXPLAIN_SIMPLE', 'GIVE_EXAMPLE', 'SIMILAR_QUESTION', 'HARDER_QUESTION']
  }
  if (question.answered) {
    return ['EXPLAIN_CONCEPT', 'EXPLAIN_SIMPLE', 'GIVE_EXAMPLE', 'SIMILAR_QUESTION', 'HARDER_QUESTION']
  }
  return ['EXPLAIN_CONCEPT', 'EXPLAIN_SIMPLE', 'GIVE_EXAMPLE', 'SIMILAR_QUESTION']
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const maybe = error as { response?: { data?: { message?: string } }; message?: string }
    return (maybe.response?.data?.message ?? maybe.message) || 'The AI tutor is temporarily unavailable. Please try again.'
  }
  return 'The AI tutor is temporarily unavailable. Please try again.'
}

function statusOf(error: unknown): number | null {
  if (error && typeof error === 'object') {
    const maybe = error as { response?: { status?: number }; status?: number }
    return maybe?.response?.status ?? maybe?.status ?? null
  }
  return null
}

function isQuestionResult(result: AiTutorExplanation | AiTutorQuestion | null): result is AiTutorQuestion {
  return result !== null && 'options' in result
}

type ErrorKind = 'rate' | 'auth' | 'forbidden' | 'gone' | 'other'

function classifyError(error: unknown): { message: string; kind: ErrorKind } {
  const status = statusOf(error)
  switch (status) {
    case 429:
      return { message: RATE_LIMITED_MESSAGE, kind: 'rate' }
    case 401:
      return { message: EXPIRED_MESSAGE, kind: 'auth' }
    case 403:
      return { message: FORBIDDEN_MESSAGE, kind: 'forbidden' }
    case 400:
    case 404:
      return { message: GONE_MESSAGE, kind: 'gone' }
    default:
      return { message: errorMessage(error), kind: 'other' }
  }
}

export function AiTutorSection({ question, sessionId, token }: { question: AIPracticeReviewQuestion; sessionId: string; token: string }) {
  const logout = useAuthStore((state) => state.logout)
  const [busyAction, setBusyAction] = useState<AiTutorAction | null>(null)
  const [lastAction, setLastAction] = useState<AiTutorAction | null>(null)
  const [result, setResult] = useState<AiTutorExplanation | AiTutorQuestion | null>(null)
  const [selectedOptionId, setSelectedOptionId] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<AiTutorAnswerResult | null>(null)
  const [alreadyAnswered, setAlreadyAnswered] = useState(false)
  const [submittedQuestionId, setSubmittedQuestionId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [errorKind, setErrorKind] = useState<ErrorKind | null>(null)

  const actions = tutorActionsFor(question)

  const answerMutation = useMutation({
    mutationFn: (optionId: string) => {
      if (!isQuestionResult(result)) throw new Error('No active tutor question.')
      setSubmittedQuestionId(result.questionId)
      return aiTutorApi.answerQuestion(result.questionId, optionId)
    },
    onSuccess: response => {
      if (!isQuestionResult(result) || result.questionId !== submittedQuestionId) return
      setFeedback(response.data.data)
      setAlreadyAnswered(false)
      setSelectedOptionId(null)
    },
    onError: (err: unknown) => {
      if (statusOf(err) === 409) {
        setAlreadyAnswered(true)
        setFeedback(null)
        setSelectedOptionId(null)
        return
      }
      setError(errorMessage(err))
    },
  })

  const interactionLocked = Boolean(busyAction) || answerMutation.isPending

  const run = async (action: AiTutorAction) => {
    if (interactionLocked || busyAction) return
    setBusyAction(action)
    setError(null)
    setErrorKind(null)
    setAlreadyAnswered(false)
    try {
      const data = await aiTutorApi.generate(sessionId, question.id, action, token)
      setResult(data)
      setFeedback(null)
      setSelectedOptionId(null)
      setLastAction(action)
    } catch (err) {
      const { message, kind } = classifyError(err)
      if (kind === 'auth') logout()
      setError(message)
      setErrorKind(kind)
    } finally {
      setBusyAction(null)
    }
  }

  const submitAnswer = () => {
    if (!selectedOptionId || interactionLocked || alreadyAnswered) return
    answerMutation.mutate(selectedOptionId)
  }

  const close = () => {
    if (busyAction) return
    setResult(null)
    setFeedback(null)
    setSelectedOptionId(null)
    setAlreadyAnswered(false)
    setError(null)
    setErrorKind(null)
  }

  const retry = () => {
    if (lastAction) run(lastAction)
    else if (actions.length > 0) run(actions[0])
  }

  return (
    <div className="mt-4 rounded-xl border border-primary/20 bg-primary/5 p-4">
      <div className="flex items-center gap-2">
        <GraduationCap size={16} className="text-primary" />
        <h4 className="text-sm font-semibold">AI tutor</h4>
      </div>

      <div className="mt-3 flex flex-wrap gap-2">
        {actions.map(action => (
          <button
            key={action}
            type="button"
            disabled={interactionLocked}
            aria-busy={busyAction === action}
            onClick={() => run(action)}
            className="inline-flex items-center gap-1.5 rounded-full border border-primary/30 px-3 py-1 text-xs font-medium text-primary hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busyAction === action && <Loader2 size={13} className="animate-spin" />}
            {ACTION_LABELS[action]}
          </button>
        ))}
      </div>

      {busyAction && (
        <p className="mt-3 flex items-center gap-2 text-sm text-muted-foreground" aria-busy="true">
          <WandSparkles size={15} className="text-primary" />
          {result ? 'Generating a new response…' : `Preparing your ${ACTION_LABELS[busyAction].toLowerCase()}…`}
        </p>
      )}

      {!busyAction && !result && !error && (
        <p className="mt-3 flex items-start gap-2 text-sm text-muted-foreground">
          <Sparkles size={14} className="mt-0.5 shrink-0 text-primary" />
          Ask the AI tutor about this question.
        </p>
      )}

      {error && (
        <div role="alert" className="mt-3 flex items-start gap-2 rounded-xl bg-red-500/10 p-3 text-sm text-destructive">
          <CircleX size={16} className="mt-0.5 shrink-0" />
          <div className="flex-1">
            <p>{error}</p>
            {errorKind === 'other' || errorKind === 'gone' || errorKind === 'forbidden' ? (
              <button type="button" onClick={retry} className="mt-1 text-xs font-medium underline underline-offset-2">
                Try again
              </button>
            ) : null}
          </div>
        </div>
      )}

      {result && !isQuestionResult(result) && (
        <div className="mt-3 grid gap-3" aria-live="polite">
          <div>
            <h5 className="text-sm font-semibold leading-6">{result.title}</h5>
            <p className="mt-1 text-sm leading-6 text-muted-foreground">{result.explanation}</p>
          </div>
          <div className="rounded-xl bg-primary/10 p-3 text-sm">
            <span className="font-semibold">Key point: </span>
            <span className="text-muted-foreground">{result.keyPoint}</span>
          </div>
          {result.misconception && (
            <div className="rounded-xl bg-amber-500/10 p-3 text-sm">
              <span className="font-semibold">Watch out: </span>
              <span className="text-muted-foreground">{result.misconception}</span>
            </div>
          )}
        </div>
      )}

      {isQuestionResult(result) && (
        <div className="mt-3 grid gap-3" aria-live="polite">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <p className="text-sm font-medium leading-6">{result.question}</p>
            <span className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs font-semibold text-muted-foreground">
              Difficulty {result.difficulty}/5
            </span>
          </div>
          {!feedback && !alreadyAnswered && (
            <div className="grid gap-2" role="radiogroup" aria-label="Tutor question options">
              {result.options.map((option, index) => {
                const isSelected = selectedOptionId === option.id
                return (
                  <button
                    key={option.id}
                    type="button"
                    role="radio"
                    aria-checked={isSelected}
                    aria-disabled={interactionLocked}
                    disabled={interactionLocked}
                    onClick={() => setSelectedOptionId(option.id)}
                    className={`flex items-center gap-3 rounded-xl border p-3 text-left text-sm transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
                      isSelected ? 'border-primary bg-primary/5 font-medium text-primary' : 'border-border hover:bg-muted'
                    }`}
                  >
                    <span
                      className={`grid size-5 shrink-0 place-items-center rounded-full border text-[11px] font-semibold ${
                        isSelected ? 'border-primary bg-primary text-primary-foreground' : 'border-muted-foreground/40 text-muted-foreground'
                      }`}
                    >
                      {OPTION_LETTERS[index]}
                    </span>
                    <span>{option.text}</span>
                  </button>
                )
              })}
            </div>
          )}
          <p className="flex items-start gap-2 rounded-xl bg-muted p-3 text-xs leading-5 text-muted-foreground">
            <Lightbulb size={14} className="mt-0.5 shrink-0 text-amber-500" />
            {result.hint}
          </p>
          {feedback && (
            <div
              className={`flex items-start gap-2 rounded-xl p-3 text-sm ${
                feedback.correct ? 'bg-emerald-500/10 text-emerald-700' : 'bg-red-500/10 text-destructive'
              }`}
              role="status"
            >
              {feedback.correct ? <Check size={16} className="mt-0.5 shrink-0" /> : <CircleX size={16} className="mt-0.5 shrink-0" />}
              <div>
                <p className="font-semibold">{feedback.correct ? 'Correct!' : 'Not quite.'}</p>
                <p className="mt-0.5 text-muted-foreground">Correct answer: {feedback.correctAnswer}</p>
              </div>
            </div>
          )}
          {alreadyAnswered && (
            <div className="flex items-start gap-2 rounded-xl bg-muted p-3 text-sm text-muted-foreground" role="status">
              <Check size={16} className="mt-0.5 shrink-0" />
              {ALREADY_ANSWERED_MESSAGE}
            </div>
          )}
          <div className="flex flex-wrap items-center gap-2">
            {!feedback && !alreadyAnswered && (
              <button
                type="button"
                onClick={submitAnswer}
                disabled={!selectedOptionId || interactionLocked}
                className="inline-flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:cursor-not-allowed disabled:opacity-50"
              >
                {answerMutation.isPending && <Loader2 size={15} className="animate-spin" />}
                Check answer
              </button>
            )}
            <button
              type="button"
              disabled={interactionLocked}
              onClick={() => run(lastAction ?? 'SIMILAR_QUESTION')}
              className="inline-flex items-center gap-1.5 rounded-xl border border-border px-3 py-2 text-sm hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Sparkles size={14} className="text-primary" />
              Try another
            </button>
            <button
              type="button"
              onClick={close}
              disabled={Boolean(busyAction)}
              className="rounded-xl px-3 py-2 text-sm text-muted-foreground hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
            >
              Close
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
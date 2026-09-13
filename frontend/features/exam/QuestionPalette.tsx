import { Check, Flag } from 'lucide-react'
import type { Question } from '@/types'
import { cn } from '@/lib/utils'

export type QuestionState = 'current' | 'answered' | 'unanswered' | 'marked' | 'answered-marked'

export function questionState(questionId: string, index: number, currentIndex: number, answers: Record<string, string>, review: Record<string, boolean>): QuestionState {
  if (index === currentIndex) return 'current'
  const answered = Boolean(answers[questionId])
  const marked = Boolean(review[questionId])
  if (answered && marked) return 'answered-marked'
  if (answered) return 'answered'
  if (marked) return 'marked'
  return 'unanswered'
}

const STATE_LABEL: Record<QuestionState, string> = {
  current: 'current question',
  answered: 'answered',
  unanswered: 'unanswered',
  marked: 'marked for review',
  'answered-marked': 'answered and marked for review',
}

export function stateAriaLabel(questionId: string, index: number, currentIndex: number, answers: Record<string, string>, review: Record<string, boolean>): string {
  const state = questionState(questionId, index, currentIndex, answers, review)
  return `Question ${index + 1}, ${STATE_LABEL[state]}. Go to question ${index + 1}.`
}

function PaletteButton({ question, index, currentIndex, answers, review, onNavigate }: { question: Question; index: number; currentIndex: number; answers: Record<string, string>; review: Record<string, boolean>; onNavigate: (index: number) => void }) {
  const state = questionState(question.id, index, currentIndex, answers, review)
  const classes: Record<QuestionState, string> = {
    current: 'bg-primary text-primary-foreground ring-2 ring-primary ring-offset-2 ring-offset-card',
    answered: 'bg-emerald-500/15 text-emerald-700',
    unanswered: 'bg-muted text-muted-foreground',
    marked: 'bg-amber-500/10 text-amber-700 border border-amber-400/70',
    'answered-marked': 'bg-emerald-500/15 text-emerald-700 border border-amber-400/70',
  }
  return (
    <button
      type="button"
      onClick={() => onNavigate(index)}
      aria-current={state === 'current' ? 'true' : undefined}
      aria-label={stateAriaLabel(question.id, index, currentIndex, answers, review)}
      className={cn(
        'relative grid size-9 place-items-center rounded-lg text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:scale-95',
        classes[state],
      )}
    >
      {index + 1}
      {(state === 'marked' || state === 'answered-marked') && <Flag size={9} className="absolute right-0.5 top-0.5 text-amber-600" strokeWidth={3} />}
      {(state === 'answered' || state === 'answered-marked') && <span className="absolute bottom-1 left-1 grid size-3 place-items-center rounded-full bg-emerald-500 text-white"><Check size={8} strokeWidth={3.5} /></span>}
    </button>
  )
}

export function QuestionPalette({ questions, currentIndex, answers, review, onNavigate }: { questions: Question[]; currentIndex: number; answers: Record<string, string>; review: Record<string, boolean>; onNavigate: (index: number) => void }) {
  return (
    <div>
      <div className="grid grid-cols-5 gap-2" role="navigation" aria-label="Question palette">
        {questions.map((question, index) => (
          <PaletteButton key={question.id} question={question} index={index} currentIndex={currentIndex} answers={answers} review={review} onNavigate={onNavigate} />
        ))}
      </div>
      <div className="mt-5 space-y-2 text-xs leading-5 text-muted-foreground">
        <p className="flex items-center gap-2"><span className="size-3 rounded bg-primary" /> Current question</p>
        <p className="flex items-center gap-2"><span className="size-3 rounded bg-emerald-500/40" /> Answered</p>
        <p className="flex items-center gap-2"><span className="size-3 rounded bg-amber-500/40" /> Marked for review</p>
      </div>
    </div>
  )
}
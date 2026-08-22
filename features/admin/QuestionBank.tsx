'use client'

import { useMemo, useState } from 'react'
import { FileUp, Plus, Search, SlidersHorizontal, Trash2, UploadCloud } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Card } from '@/components/shared'
import type { Question } from '@/types'

const seedQuestions: Question[] = [
  { id: 'q1', text: 'Which principle describes the separation of concerns in software design?', options: ['Encapsulation', 'Modularity', 'Inheritance', 'Polymorphism'] },
  { id: 'q2', text: 'Explain why formative assessment is useful during a course.', options: [] },
  { id: 'q3', text: 'Which HTTP method is conventionally used to update a resource?', options: ['GET', 'POST', 'PUT', 'DELETE'] },
]

export function QuestionBank() {
  const navigate = useNavigate()
  const [questions, setQuestions] = useState(seedQuestions)
  const [search, setSearch] = useState('')
  const [type, setType] = useState('ALL')
  const [uploadName, setUploadName] = useState('')
  const filtered = useMemo(() => questions.filter((item) => item.text.toLowerCase().includes(search.toLowerCase()) && (type === 'ALL' || (item.options.length ? 'MCQ' : 'DESCRIPTIVE') === type)), [questions, search, type])

  return <>
    <header className="mb-8"><p className="text-xs font-semibold uppercase tracking-widest text-primary">ADMIN WORKSPACE</p><h1 className="mt-2 text-3xl font-semibold tracking-tight">Question bank</h1><p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">Search, review, and prepare questions for your exams. Import actions remain ready for the verified Spring Boot upload contract.</p></header>
    <div className="grid gap-4 lg:grid-cols-[1fr_300px]">
      <Card>
        <div className="flex flex-col gap-3 sm:flex-row"><label className="relative min-w-0 flex-1"><span className="sr-only">Search questions</span><Search className="pointer-events-none absolute left-3 top-3 text-muted-foreground" size={16}/><input className="field pl-9" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search questions" /></label><label className="sm:w-44"><span className="sr-only">Filter question type</span><select className="field" value={type} onChange={(event) => setType(event.target.value)}><option value="ALL">All types</option><option value="MCQ">MCQ</option><option value="DESCRIPTIVE">Descriptive</option></select></label></div>
        <div className="mt-5 flex items-center justify-between gap-3 text-sm text-muted-foreground"><span>{filtered.length} question{filtered.length === 1 ? '' : 's'}</span><button className="flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-xs hover:bg-muted active:scale-[.98]" type="button"><SlidersHorizontal size={14}/> Filters</button></div>
        <div className="mt-4 space-y-3">{filtered.map((item, index) => <article key={item.id} className="flex min-w-0 items-start justify-between gap-3 rounded-xl bg-muted p-4"><div className="min-w-0"><p className="text-xs text-primary">Question {index + 1} · {item.options.length ? 'MCQ' : 'Descriptive'}</p><p className="mt-1 break-words text-sm font-medium">{item.text}</p>{item.options.length > 0 && <p className="mt-2 break-words text-xs text-muted-foreground">{item.options.join(' · ')}</p>}</div><button aria-label={`Delete question ${index + 1}`} className="shrink-0 rounded-lg p-2 text-muted-foreground hover:bg-background hover:text-destructive" type="button" onClick={() => setQuestions((current) => current.filter((question) => question.id !== item.id))}><Trash2 size={16}/></button></article>)}{filtered.length === 0 && <p className="py-10 text-center text-sm text-muted-foreground">No questions match your filters.</p>}</div>
      </Card>
      <div className="space-y-4"><Card><div className="flex items-center gap-3"><div className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary"><Plus size={18}/></div><div><h2 className="font-semibold">Create question</h2><p className="text-xs text-muted-foreground">Use the verified authoring flow.</p></div></div><button className="mt-5 w-full rounded-xl bg-primary px-4 py-3 text-sm font-medium text-primary-foreground hover:opacity-90 active:scale-[.99]" type="button" onClick={() => navigate('/teacher/exams/create')}>Open authoring form</button></Card><Card><div className="flex items-center gap-3"><FileUp className="text-primary" size={20}/><div><h2 className="font-semibold">Bulk import</h2><p className="text-xs text-muted-foreground">CSV or Excel preview before saving.</p></div></div><label className="mt-4 flex cursor-pointer flex-col items-center rounded-xl border border-dashed border-border p-5 text-center hover:bg-muted"><UploadCloud className="text-muted-foreground" size={22}/><span className="mt-2 text-xs font-medium">{uploadName || 'Choose a file'}</span><input className="sr-only" type="file" accept=".csv,.xlsx,.xls" onChange={(event) => setUploadName(event.target.files?.[0]?.name || '')}/></label><p className="mt-3 text-xs leading-5 text-muted-foreground">Upload validation and persistence will activate when the backend contract is provided.</p></Card></div>
    </div>
  </>
}

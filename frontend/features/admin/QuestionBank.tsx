'use client'

import { useMemo, useState } from 'react'
import { Search, Trash2, RefreshCcw } from 'lucide-react'
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query'
import { Card, Header } from '@/components/shared'
import { questionApi } from '@/services/questionApi'

export function QuestionBank() {
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const questionsQuery = useQuery({
    queryKey: ['question-bank'],
    queryFn: async () => (await questionApi.listAll()).data.data,
    retry: 1,
  })
  const removeMutation = useMutation({
    mutationFn: (id: string) => questionApi.remove(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['question-bank'] }),
  })
  const filtered = useMemo(
    () => (questionsQuery.data ?? []).filter((item) => item.text.toLowerCase().includes(search.toLowerCase())),
    [questionsQuery.data, search],
  )

  return (
    <>
      <Header
        title="Question bank"
        description="Browse the live question data stored in the backend database."
      />
      <Card>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">Search questions</span>
            <Search className="pointer-events-none absolute left-3 top-3 text-muted-foreground" size={16} />
            <input className="field pl-9" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search questions" />
          </label>
          <button className="flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-xs hover:bg-muted" type="button" onClick={() => questionsQuery.refetch()}>
            <RefreshCcw size={14} /> Refresh
          </button>
        </div>
        <div className="mt-5 text-sm text-muted-foreground">{filtered.length} question{filtered.length === 1 ? '' : 's'}</div>
        <div className="mt-4 space-y-3">
          {questionsQuery.isPending ? (
            <div className="h-24 animate-pulse rounded-xl bg-muted" />
          ) : questionsQuery.isError ? (
            <p className="py-8 text-center text-sm text-destructive">Unable to load questions from the database.</p>
          ) : filtered.length ? (
            filtered.map((item, index) => (
              <article key={item.id} className="flex min-w-0 items-start justify-between gap-3 rounded-xl bg-muted p-4">
                <div className="min-w-0">
                  <p className="text-xs text-primary">Question {index + 1} · {item.options.length ? 'MCQ' : 'Descriptive'}</p>
                  <p className="mt-1 break-words text-sm font-medium">{item.text}</p>
                  {item.options.length > 0 && <p className="mt-2 break-words text-xs text-muted-foreground">{item.options.join(' · ')}</p>}
                </div>
                <button
                  aria-label={`Delete question ${index + 1}`}
                  className="shrink-0 rounded-lg p-2 text-muted-foreground hover:bg-background hover:text-destructive"
                  type="button"
                  onClick={() => removeMutation.mutate(item.id)}
                >
                  <Trash2 size={16} />
                </button>
              </article>
            ))
          ) : (
            <p className="py-10 text-center text-sm text-muted-foreground">No questions match your search.</p>
          )}
        </div>
      </Card>
    </>
  )
}
